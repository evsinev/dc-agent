package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.config.model.TZipArchiveVersionConfig;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.exception.ProblemException;
import com.payneteasy.dcagent.core.modules.zipversion.DirLocks;
import com.payneteasy.dcagent.core.modules.zipversion.Durability;
import com.payneteasy.dcagent.core.modules.zipversion.PointerFile;
import com.payneteasy.dcagent.core.modules.zipversion.ReloadClient;
import com.payneteasy.dcagent.core.modules.zipversion.UploadTempFiles;
import com.payneteasy.dcagent.core.modules.zipversion.VersionArchive;
import com.payneteasy.dcagent.core.modules.zipversion.VersionPublisher;
import com.payneteasy.dcagent.core.modules.zipversion.ZipArchiveVersionSettings;
import com.payneteasy.dcagent.jetty.CommandAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Semaphore;

/**
 * {@code POST /zip-archive-version/{name}/{version}}, body the raw ZIP: publish an immutable
 * version directory, switch the pointer file, ask the service to reload and wait for its answer —
 * one transaction with three outcomes (issue #98):
 * <ul>
 *     <li>confirmed — 200, {@code published|present active <version> sha256 <digest>};</li>
 *     <li>rolled back — the service's 409/422/503, or 502: the pointer is back where it was;</li>
 *     <li>unknown — 504 (pointer back, the service may still be finishing) or 500 (an fsync failed,
 *     the state on disk is the intended one). A retry of the same call is always safe.</li>
 * </ul>
 * Order: authorize → config → version name → upload permit (before the body) → body to a temp
 * file → pass 1 → {@code dir} → directory lock (held to the end) → publish → temp file gone,
 * permit released → pointer → reload → outcome → lock released → the answer written.
 */
public class ZipArchiveVersionServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(ZipArchiveVersionServlet.class);

    static final String SEGMENT = "/zip-archive-version/";

    /** At most this many uploads are read or extracted at once per agent; more → 503 before the body. */
    public static final int UPLOADS_IN_FLIGHT = 2;

    private final IConfigService    configService;
    private final Duration          idleTimeout;
    private final ReloadClient      reloadClient;
    private final UploadTempFiles   uploads;
    private final Semaphore         uploadPermits;
    private final DirLocks          locks;
    private final Durability        durability;
    private final VersionPublisher  publisher;
    private final CommandAuth       commandAuth = new CommandAuth();

    public ZipArchiveVersionServlet(IConfigService aConfigService, Duration aIdleTimeout, ReloadClient aReloadClient,
                                    UploadTempFiles aUploads) {
        this(aConfigService, aIdleTimeout, aReloadClient, aUploads, new Semaphore(UPLOADS_IN_FLIGHT),
                new DirLocks(ZipArchiveVersionSettings.LOCK_WAIT), Durability.FS);
    }

    ZipArchiveVersionServlet(IConfigService aConfigService, Duration aIdleTimeout, ReloadClient aReloadClient,
                             UploadTempFiles aUploads, Semaphore aUploadPermits, DirLocks aLocks, Durability aDurability) {
        configService = aConfigService;
        idleTimeout   = aIdleTimeout;
        reloadClient  = aReloadClient;
        uploads       = aUploads;
        uploadPermits = aUploadPermits;
        locks         = aLocks;
        durability    = aDurability;
        publisher     = new VersionPublisher(aDurability);
    }

    @Override
    protected void doPost(HttpServletRequest aRequest, HttpServletResponse aResponse) throws IOException {
        Target target = Target.parse(aRequest.getContextPath(), aRequest.getRequestURI());
        // nothing — body, dir, log of the request — before the command is authorized
        TZipArchiveVersionConfig config = commandAuth.authorize(aRequest, target.name(), TaskType.ZIP_ARCHIVE_VERSION,
                configService::getZipArchiveVersionConfig);
        ZipArchiveVersionSettings settings = ZipArchiveVersionSettings.from(target.name(), config, idleTimeout);
        if (target.version() == null) {
            throw new ProblemException(400, "the path must be /zip-archive-version/{name}/{version}");
        }
        String problem = settings.versionProblem(target.version());
        if (problem != null) {
            throw new ProblemException(400, problem);
        }

        String line = transaction(aRequest, settings, target.version());

        // after the lock is released: a cut connection here changes nothing, the service said yes
        aResponse.setStatus(HttpServletResponse.SC_OK);
        aResponse.setContentType("text/plain; charset=utf-8");
        PrintWriter writer = aResponse.getWriter();
        writer.write(line + "\n");
        writer.flush();
    }

    private String transaction(HttpServletRequest aRequest, ZipArchiveVersionSettings aSettings, String aVersion) throws IOException {
        if (!uploadPermits.tryAcquire()) {
            throw new ProblemException(503, "busy: " + UPLOADS_IN_FLIGHT + " uploads are already in flight");
        }
        boolean permitHeld = true;
        try (UploadTempFiles.Upload upload = uploads.create()) {
            long declared = aRequest.getContentLengthLong();
            if (declared > aSettings.maxUploadBytes()) {
                throw UploadTempFiles.tooLarge(aSettings.maxUploadBytes());
            }
            upload.copyFrom(aRequest.getInputStream(), aSettings.maxUploadBytes());

            try (VersionArchive archive = VersionArchive.read(upload.path(), aSettings.maxBytes(), aSettings.maxEntries())) {
                Path realDir = publisher.prepareDir(aSettings.dir());
                try (DirLocks.Held ignored = locks.acquire(realDir)) {
                    VersionPublisher.Result published = publisher.publish(realDir, aVersion, archive);
                    String digest = archive.digest();
                    int    files  = archive.files().size();

                    // the upload is no longer needed: its disk and its permit are free during the reload
                    archive.close();
                    upload.close();
                    uploadPermits.release();
                    permitHeld = false;

                    PointerFile pointer = new PointerFile(realDir, aSettings.pointerName(), durability);
                    pointer.switchTo(aVersion);

                    ReloadClient.Outcome outcome = reloadClient.reload(aSettings, aVersion);
                    LOG.info("zip-archive-version {} {}: {} files, sha256 {}, {}, service {}", aSettings.name(), aVersion,
                            files, digest, published, outcome);
                    if (outcome instanceof ReloadClient.Answered answered && answered.confirmed()) {
                        pointer.settle();
                        return published.name().toLowerCase(Locale.ROOT) + " active " + aVersion + " sha256 " + digest;
                    }
                    PointerFile.Rollback rollback;
                    try {
                        rollback = pointer.rollback(aVersion);
                    } catch (IOException e) {
                        // a DurabilityException (the rollback is on disk, its sync failed) passes as it is
                        LOG.error("Rollback of {} in {} failed", aVersion, realDir, e);
                        throw new ProblemException(500, "rollback failed, pointer names " + aVersion
                                + " (the service did not confirm it: " + describe(outcome) + "); a retry is safe");
                    }
                    throw failure(outcome, rollback, aVersion);
                }
            }
        } finally {
            if (permitHeld) {
                uploadPermits.release();
            }
        }
    }

    /** The service did not confirm; the pointer has been dealt with. */
    static ProblemException failure(ReloadClient.Outcome aOutcome, PointerFile.Rollback aRollback, String aVersion) {
        String pointer = switch (aRollback.kind()) {
            case RESTORED        -> "pointer back to " + aRollback.value();
            case REMOVED         -> "pointer removed (there was none before)";
            case CHANGED_BY_HAND -> "pointer changed meanwhile to " + (aRollback.value() == null ? "-" : aRollback.value()) + ", not touched";
            case NO_PREVIOUS     -> "pointer left at " + aVersion + " (confirmed by an earlier call)";
        };
        if (aOutcome instanceof ReloadClient.Answered answered) {
            int status = answered.status();
            if (status == 409 || status == 422 || status == 503) {
                return new ProblemException(status, "service: " + answered.reason() + "; " + pointer);
            }
            return new ProblemException(502, "service answered " + status + ": " + answered.reason() + "; " + pointer);
        }
        if (aOutcome instanceof ReloadClient.TimedOut) {
            return new ProblemException(504, "no answer from the service within waitTimeout, the outcome is unknown"
                    + " (it may still be finishing); " + pointer + "; a retry is safe");
        }
        return new ProblemException(502, "the reload call failed (" + ((ReloadClient.Failed) aOutcome).cause() + "); " + pointer);
    }

    private static String describe(ReloadClient.Outcome aOutcome) {
        if (aOutcome instanceof ReloadClient.Answered answered) {
            return "answered " + answered.status();
        }
        return aOutcome instanceof ReloadClient.TimedOut ? "no answer within waitTimeout" : "the call failed";
    }

    /**
     * {@code name} and {@code version} from the raw URI after exactly {@code <context>/zip-archive-version/}
     * (a context named like the endpoint must not shift the name); {@code version} null when the
     * path has another shape.
     */
    record Target(String name, String version) {

        static Target parse(String aContextPath, String aUri) {
            String prefix = (aContextPath == null ? "" : aContextPath) + SEGMENT;
            if (aUri == null || !aUri.startsWith(prefix)) {
                return new Target(null, null);
            }
            // split with -1 keeps empty segments: "name//v1" and "name/v1/" are other shapes, not name/v1
            String[] parts = aUri.substring(prefix.length()).split("/", -1);
            String   name  = parts[0].isEmpty() ? null : parts[0];
            if (parts.length != 2 || parts[1].isEmpty()) {
                return new Target(name, null);
            }
            return new Target(name, parts[1]);
        }
    }
}
