package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.exception.ProblemException;
import com.payneteasy.dcagent.core.util.SegmentNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * The pointer file {@code <dir>/<name>}: exactly {@code <version>\n}. Steps 7 and 9 of
 * {@code zip-archive-version}, under the directory lock.
 * <p>Before the pointer changes, its previous value is recorded on disk in {@code .<name>.previous}
 * — the old value with a newline, or an empty file when there was no pointer — so a rollback
 * survives the agent's own death between the switch and the service's answer: a retry finds the
 * pointer already switched and still knows where it came from. {@code .previous} is written only
 * when the pointer actually changes, so a retry never overwrites it with the version itself.
 */
public final class PointerFile {

    private static final Logger LOG = LoggerFactory.getLogger(PointerFile.class);

    /** 64 characters of a name and a newline. */
    private static final int LIMIT = 65;

    public enum Switch { SWITCHED, ALREADY }

    public enum RollbackKind {
        /** the value from {@code .previous} is back */
        RESTORED,
        /** {@code .previous} was empty: there was no pointer, the file is removed */
        REMOVED,
        /** the pointer no longer names the version — changed by hand meanwhile, not ours to touch */
        CHANGED_BY_HAND,
        /** no {@code .previous}: the pointer was not switched by this command, left as it is */
        NO_PREVIOUS
    }

    public record Rollback(RollbackKind kind, String value) {
    }

    private final Path       dir;
    private final String     name;
    private final Durability durability;

    public PointerFile(Path aRealDir, String aName, Durability aDurability) {
        dir        = aRealDir;
        name       = aName;
        durability = aDurability;
    }

    String previousName() {
        return "." + name + ".previous";
    }

    /** The version the pointer names, or null when it is absent, a link or does not parse (logged). */
    public String read() throws IOException {
        byte[] bytes = VersionFiles.readSmall(dir.resolve(name), LIMIT);
        if (bytes == null) {
            if (Files.exists(dir.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                LOG.warn("Pointer {} is not a regular file of at most {} bytes, treated as absent", dir.resolve(name), LIMIT);
            }
            return null;
        }
        String value = parse(bytes);
        if (value == null) {
            LOG.warn("Pointer {} does not hold <version>\\n, treated as absent", dir.resolve(name));
        }
        return value;
    }

    /** Brings the pointer to {@code aVersion}; {@code dir} is synced in both branches. */
    public Switch switchTo(String aVersion) throws IOException {
        String current = read();
        if (aVersion.equals(current)) {
            // a retry after a failed sync must sync the directory it skipped
            VersionFiles.syncDir(dir, "pointer-unchanged", durability);
            return Switch.ALREADY;
        }
        byte[] previous = current == null ? new byte[0] : line(current);
        VersionFiles.writeAtomically(dir, previousName(), previous, "previous", durability);
        VersionFiles.writeAtomically(dir, name, line(aVersion), "pointer", durability);
        LOG.info("Pointer {} switched from {} to {}", dir.resolve(name), current == null ? "-" : current, aVersion);
        return Switch.SWITCHED;
    }

    /**
     * Puts the pointer back after the service did not confirm {@code aVersion} — only if it still
     * names {@code aVersion}. An unreadable {@code .previous} → 500, the pointer left as it is.
     */
    public Rollback rollback(String aVersion) throws IOException {
        String current = read();
        if (!aVersion.equals(current)) {
            LOG.warn("Pointer {} names {} instead of {}: changed meanwhile, not rolled back", dir.resolve(name),
                    current == null ? "-" : current, aVersion);
            return new Rollback(RollbackKind.CHANGED_BY_HAND, current);
        }
        Path   previousFile = dir.resolve(previousName());
        byte[] previous     = VersionFiles.readSmall(previousFile, LIMIT);
        if (previous == null) {
            if (Files.exists(previousFile, LinkOption.NOFOLLOW_LINKS)) {
                throw unreadablePrevious(aVersion);
            }
            LOG.warn("Pointer {} names {} but there is no {}: not switched by this command, left as it is",
                    dir.resolve(name), aVersion, previousName());
            return new Rollback(RollbackKind.NO_PREVIOUS, aVersion);
        }
        if (previous.length == 0) {
            Files.delete(dir.resolve(name));
            VersionFiles.syncDir(dir, "rollback-removed", durability);
            LOG.info("Pointer {} removed: there was none before {}", dir.resolve(name), aVersion);
            return new Rollback(RollbackKind.REMOVED, null);
        }
        String value = parse(previous);
        if (value == null) {
            throw unreadablePrevious(aVersion);
        }
        VersionFiles.writeAtomically(dir, name, line(value), "rollback", durability);
        LOG.info("Pointer {} rolled back from {} to {}", dir.resolve(name), aVersion, value);
        return new Rollback(RollbackKind.RESTORED, value);
    }

    private ProblemException unreadablePrevious(String aVersion) {
        return new ProblemException(500, "rollback impossible: " + previousName() + " is unreadable, the pointer names " + aVersion);
    }

    /** {@code <name>\n} exactly — one trailing newline, no {@code \r}, no spaces — or null. */
    static String parse(byte[] aBytes) {
        if (aBytes.length < 2 || aBytes[aBytes.length - 1] != '\n') {
            return null;
        }
        String value = new String(aBytes, 0, aBytes.length - 1, US_ASCII);
        return SegmentNames.isValid(value) ? value : null;
    }

    private static byte[] line(String aValue) {
        return (aValue + "\n").getBytes(US_ASCII);
    }
}
