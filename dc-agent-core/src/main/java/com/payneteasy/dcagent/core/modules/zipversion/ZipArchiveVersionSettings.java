package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.config.model.TZipArchiveVersionConfig;
import com.payneteasy.dcagent.core.util.SegmentNames;
import com.payneteasy.dcagent.core.util.Strings;
import com.payneteasy.dcagent.core.util.Units;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A {@link TZipArchiveVersionConfig} checked once, right after the call is authorized: every
 * limit parsed (with a finite default), {@code versionFile} directly in {@code dir}, the reload
 * request buildable. A bad field is a {@link ConfigValueException} naming the field, never the value.
 */
public record ZipArchiveVersionSettings(
        String              name,
        Path                dir,
        String              pointerName,
        Pattern             versionPattern,
        long                maxUploadBytes,
        long                maxBytes,
        int                 maxEntries,
        String              reloadUrl,
        Map<String, String> reloadHeaders,
        String              reloadBody,
        Duration            waitTimeout
) {

    public static final String   DEFAULT_MAX_UPLOAD_BYTES = "100mb";
    public static final String   DEFAULT_MAX_BYTES        = "500mb";
    public static final String   DEFAULT_MAX_ENTRIES      = "10k";
    public static final String   DEFAULT_WAIT_TIMEOUT     = "5m";
    /** How long a call waits for the directory lock before 503. */
    public static final Duration LOCK_WAIT                = Duration.ofMinutes(1);

    static final String VERSION_PLACEHOLDER = "${version}";

    /** A valid segment, substituted when the reload request is test-built at load. */
    private static final String PROBE_VERSION = "v0";

    /**
     * @param aIdleTimeout the agent's connector idle timeout: the lock wait plus {@code waitTimeout}
     *                     must fit in it, or the connection is cut while the call is still silent
     */
    public static ZipArchiveVersionSettings from(String aName, TZipArchiveVersionConfig aConfig, Duration aIdleTimeout) {
        Fields fields = new Fields(aName);

        Path   dir         = fields.check("dir", () -> absolute(aConfig.getDir()));
        if (dir.getParent() == null) {
            throw fields.error("dir", "must not be the file system root");
        }
        Path   versionFile = fields.check("versionFile", () -> absolute(aConfig.getVersionFile()));
        if (!dir.equals(versionFile.getParent())) {
            throw fields.error("versionFile", "must lie directly in dir");
        }
        String pointerName = versionFile.getFileName().toString();
        if (!SegmentNames.isValid(pointerName)) {
            throw fields.error("versionFile", "file name must be 1-64 of [A-Za-z0-9._-], starting with a letter or digit");
        }

        Pattern versionPattern = null;
        if (Strings.hasText(aConfig.getVersionPattern())) {
            try {
                versionPattern = Pattern.compile(aConfig.getVersionPattern());
            } catch (PatternSyntaxException e) {
                throw fields.error("versionPattern", "not a valid regular expression");
            }
        }

        long     maxUploadBytes = fields.positive("maxUploadBytes", aConfig.getMaxUploadBytes(), DEFAULT_MAX_UPLOAD_BYTES, Units::parseSize);
        long     maxBytes       = fields.positive("maxBytes"      , aConfig.getMaxBytes()      , DEFAULT_MAX_BYTES       , Units::parseSize);
        long     maxEntries     = fields.positive("maxEntries"    , aConfig.getMaxEntries()    , DEFAULT_MAX_ENTRIES     , Units::parseCount);
        if (maxEntries > Integer.MAX_VALUE) {
            throw fields.error("maxEntries", "is too large");
        }
        Duration waitTimeout    = fields.check("waitTimeout", () -> Units.parseDuration(aConfig.getWaitTimeout(), DEFAULT_WAIT_TIMEOUT));
        if (waitTimeout.isNegative() || waitTimeout.isZero()) {
            throw fields.error("waitTimeout", "must be positive");
        }
        // subtracted from the idle timeout, not added to waitTimeout: a huge waitTimeout must not overflow
        if (waitTimeout.compareTo(aIdleTimeout.minus(LOCK_WAIT)) >= 0) {
            throw fields.error("waitTimeout", "plus the " + LOCK_WAIT.toMinutes() + "m lock wait must be below the agent's"
                    + " WEB_SERVER_IDLE_TIMEOUT (" + aIdleTimeout + "); lower it or raise the parameter");
        }

        if (Strings.isEmpty(aConfig.getReloadUrl())) {
            throw fields.error("reloadUrl", "is required");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (aConfig.getReloadHeaders() != null) {
            for (Map.Entry<String, String> header : aConfig.getReloadHeaders().entrySet()) {
                checkHeader(fields, header.getKey(), header.getValue());
                headers.put(header.getKey(), header.getValue());
            }
        }

        ZipArchiveVersionSettings settings = new ZipArchiveVersionSettings(aName, dir, pointerName, versionPattern,
                maxUploadBytes, maxBytes, (int) maxEntries, aConfig.getReloadUrl(),
                Collections.unmodifiableMap(headers), aConfig.getReloadBody(), waitTimeout);
        settings.probeReloadRequest(fields);
        return settings;
    }

    public Path versionFile() {
        return dir.resolve(pointerName);
    }

    /**
     * Why {@code aVersion} cannot be published here, or null when it can — the name rules, the
     * pattern, and the reload request built for it. The reason never quotes the value.
     */
    public String versionProblem(String aVersion) {
        if (!SegmentNames.isValid(aVersion)) {
            return "a version must be 1-64 of [A-Za-z0-9._-], starting with a letter or digit";
        }
        if (aVersion.equals(pointerName)) {
            return "a version must not be named like the pointer file";
        }
        if (versionPattern != null && !versionPattern.matcher(aVersion).matches()) {
            return "the version does not match versionPattern of the command";
        }
        // The probe at load cannot cover every version (`http://${version}.internal/` takes `v0`
        // but not `v1_rc` as a host name): build the real request now, before anything on disk.
        try {
            reloadRequest(aVersion);
        } catch (URISyntaxException | RuntimeException e) {
            return "the reload request of the command cannot be built for this version";
        }
        return null;
    }

    /**
     * The reload call for a version: POST {@code reloadUrl} (and {@code reloadBody}) with
     * {@code ${version}} substituted, {@code reloadHeaders} added. The version must have passed
     * {@link #versionProblem}; its characters need no URL encoding.
     */
    public HttpRequest reloadRequest(String aVersion) throws URISyntaxException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(new URI(reloadUrl.replace(VERSION_PLACEHOLDER, aVersion)))
                .timeout(waitTimeout)
                .POST(reloadBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(reloadBody.replace(VERSION_PLACEHOLDER, aVersion), UTF_8));
        reloadHeaders.forEach(builder::header);
        return builder.build();
    }

    @Override
    public String toString() {
        // reloadHeaders hold the service's token
        return "ZipArchiveVersionSettings[name=" + name + ", dir=" + dir + ", pointer=" + pointerName
                + ", reloadHeaders=" + reloadHeaders.keySet() + ", waitTimeout=" + waitTimeout + "]";
    }

    /**
     * Builds the request once with a probe version: what the JDK refuses (a restricted header like
     * {@code Host}, a character it does not accept, a bad URI) is a config error at load, not an
     * exception after the pointer has been switched. JDK messages quote header values — dropped.
     */
    private void probeReloadRequest(Fields aFields) {
        URI uri;
        try {
            uri = new URI(reloadUrl.replace(VERSION_PLACEHOLDER, PROBE_VERSION));
        } catch (URISyntaxException e) {
            throw aFields.error("reloadUrl", "not a valid http(s) URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
            throw aFields.error("reloadUrl", "not a valid http(s) URI");
        }
        // The caller chooses the version: it may go into the path and the query, never into the
        // scheme, host or port — the target of the call is the config's, not the caller's.
        int authorityEnd = reloadUrl.indexOf('/', reloadUrl.indexOf("//") + 2);
        String head = authorityEnd < 0 ? reloadUrl : reloadUrl.substring(0, authorityEnd);
        if (head.contains(VERSION_PLACEHOLDER)) {
            throw aFields.error("reloadUrl", VERSION_PLACEHOLDER + " is allowed in the path and the query only");
        }
        for (Map.Entry<String, String> header : reloadHeaders.entrySet()) {
            try {
                HttpRequest.newBuilder(uri).header(header.getKey(), header.getValue());
            } catch (RuntimeException e) {
                throw aFields.error("reloadHeaders", "header " + header.getKey() + ": not accepted by the HTTP client");
            }
        }
        try {
            reloadRequest(PROBE_VERSION);
        } catch (URISyntaxException | RuntimeException e) {
            throw aFields.error("reloadUrl", "the reload request cannot be built");
        }
    }

    private static void checkHeader(Fields aFields, String aName, String aValue) {
        if (aName == null || aName.isEmpty() || !aName.chars().allMatch(ZipArchiveVersionSettings::isTokenChar)) {
            throw aFields.error("reloadHeaders", "a header name must be an HTTP token");
        }
        if (aValue == null || !aValue.chars().allMatch(ch -> ch == '\t' || (ch >= 0x20 && ch <= 0x7e))) {
            throw aFields.error("reloadHeaders", "header " + aName + ": the value must be visible ASCII, space or tab");
        }
    }

    private static boolean isTokenChar(int aChar) {
        return (aChar >= 'a' && aChar <= 'z') || (aChar >= 'A' && aChar <= 'Z') || (aChar >= '0' && aChar <= '9')
                || "!#$%&'*+-.^_`|~".indexOf(aChar) >= 0;
    }

    private static Path absolute(String aPath) {
        if (Strings.isEmpty(aPath)) {
            throw new IllegalArgumentException("is required");
        }
        Path path;
        try {
            path = Path.of(aPath);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("not a valid path");
        }
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("must be an absolute path");
        }
        return path.normalize();
    }

    private record Fields(String configName) {

        ConfigValueException error(String aField, String aReason) {
            return new ConfigValueException(configName, aField, aReason);
        }

        <T> T check(String aField, Supplier<T> aParse) {
            try {
                return aParse.get();
            } catch (IllegalArgumentException e) {
                // Our own parsers' messages: the expected form, never the value
                throw error(aField, e.getMessage());
            }
        }

        long positive(String aField, String aValue, String aDefault, Function<String, Long> aParse) {
            long value = check(aField, () -> aParse.apply(Strings.hasText(aValue) ? aValue : aDefault));
            if (value <= 0) {
                throw error(aField, "must be positive");
            }
            return value;
        }
    }
}
