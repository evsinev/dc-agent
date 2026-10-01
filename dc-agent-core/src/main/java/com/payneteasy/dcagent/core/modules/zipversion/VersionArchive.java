package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.util.Strings;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass 1 of {@code zip-archive-version}: the uploaded ZIP checked and digested before anything is
 * created in {@code dir}. Read with commons-compress — {@code java.util.zip} does not expose the
 * Unix mode, so it cannot tell a symbolic-link entry from a file. Any refusal is an
 * {@link ArchiveRefusedException} (400).
 * <ul>
 *     <li>every entry counts towards {@code maxEntries}, directory entries included;</li>
 *     <li>a name ending in {@code /} is a directory entry ({@code ./} too): counted, otherwise ignored —
 *     empty directories are not created and are not part of the digest;</li>
 *     <li>a name uses {@code /} only ({@code \} refused, not normalised); one leading {@code ./} is
 *     stripped; absolute names, empty, {@code .} and {@code ..} segments, control characters are refused;</li>
 *     <li>two files with one normalised name are refused, and so is a file whose name is a directory
 *     prefix (by path segment) of another entry;</li>
 *     <li>a symbolic link or any other non-regular Unix type is refused; an entry without a Unix mode
 *     is a regular file;</li>
 *     <li>sizes are counted from the bytes read, not the declared sizes; over {@code maxBytes} → refused;
 *     an archive with no file entries is refused.</li>
 * </ul>
 * The archive stays open: pass 2 ({@link #open}) reads the same entries again for the staging copy.
 */
public final class VersionArchive implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(VersionArchive.class);

    /** A file of the version: normalised path, sha256 hex and size of the contents read in pass 1. */
    public record FileEntry(String path, String sha256, long size, ZipArchiveEntry entry) {
    }

    private static final int UNIX_TYPE_MASK = 0170000;
    private static final int UNIX_REGULAR   = 0100000;
    private static final int UNIX_DIRECTORY = 0040000;
    private static final int MAX_NAME_IN_REASON = 120;

    private final ZipFile         zip;
    private final List<FileEntry> files;
    private final String          digest;
    private final long            totalBytes;

    private VersionArchive(ZipFile aZip, List<FileEntry> aFiles, String aDigest, long aTotalBytes) {
        zip        = aZip;
        files      = aFiles;
        digest     = aDigest;
        totalBytes = aTotalBytes;
    }

    public static VersionArchive read(Path aZip, long aMaxBytes, int aMaxEntries) throws IOException {
        return read(Files.newByteChannel(aZip, StandardOpenOption.READ), aMaxBytes, aMaxEntries);
    }

    /**
     * Owns {@code aChannel}: closed on every failure, by {@link #close} otherwise. commons-compress
     * 1.28 opens a path with {@code closeOnError=false} — a ZIP that fails to parse after opening
     * would leak its descriptor — so the channel is opened here, not by the library.
     */
    static VersionArchive read(SeekableByteChannel aChannel, long aMaxBytes, int aMaxEntries) throws IOException {
        ZipFile zip       = null;
        boolean succeeded = false;
        try {
            try {
                zip = ZipFile.builder()
                        .setSeekableByteChannel(aChannel)
                        // the name as stored in the header only: a Unicode Path extra field would
                        // replace it after the backslash check (and may disagree with it)
                        .setUseUnicodeExtraFields(false)
                        .get();
            } catch (IOException | RuntimeException e) {
                throw new ArchiveRefusedException("not a readable ZIP archive");
            }
            VersionArchive archive = check(zip, aMaxBytes, aMaxEntries);
            succeeded = true;
            return archive;
        } finally {
            if (!succeeded) {
                closeQuietly(zip);
                closeQuietly(aChannel);
            }
        }
    }

    /** On a failure path only: the refusal, not a close error, is what the caller must see. */
    private static void closeQuietly(Closeable aCloseable) {
        if (aCloseable == null) {
            return;
        }
        try {
            aCloseable.close();
        } catch (IOException e) {
            LOG.warn("Cannot close the uploaded archive", e);
        }
    }

    private static VersionArchive check(ZipFile aZip, long aMaxBytes, int aMaxEntries) throws IOException {
        // Names and types first, contents after: the cheap refusals do not inflate anything
        Map<String, ZipArchiveEntry> byPath  = new LinkedHashMap<>();
        int                          entries = 0;
        for (Enumeration<ZipArchiveEntry> it = aZip.getEntries(); it.hasMoreElements(); ) {
            ZipArchiveEntry entry = it.nextElement();
            if (++entries > aMaxEntries) {
                throw new ArchiveRefusedException("more than maxEntries (" + aMaxEntries + ") entries");
            }
            String  raw       = entry.getName();
            if (hasBackslash(entry.getRawName())) {
                // commons-compress turns '\' into '/' in the name of an MS-DOS-made entry: check the bytes as stored
                throw new ArchiveRefusedException("entry " + quote(raw) + " has a backslash in its name");
            }
            boolean directory = raw.endsWith("/");
            checkType(entry, directory);
            String  path      = normalise(raw, directory);
            if (directory) {
                continue;
            }
            if (!aZip.canReadEntryData(entry)) {
                throw new ArchiveRefusedException("entry " + quote(raw) + " uses unsupported compression or encryption");
            }
            if (byPath.putIfAbsent(path, entry) != null) {
                throw new ArchiveRefusedException("two entries are named " + quote(path));
            }
        }
        if (byPath.isEmpty()) {
            throw new ArchiveRefusedException("no file entries");
        }
        for (String path : byPath.keySet()) {
            for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1)) {
                if (byPath.containsKey(path.substring(0, slash))) {
                    throw new ArchiveRefusedException("file " + quote(path.substring(0, slash)) + " is also a directory of "
                            + quote(path));
                }
            }
        }

        List<FileEntry>     files  = new ArrayList<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        long                total  = 0;
        byte[]              buffer = new byte[64 * 1024];
        for (Map.Entry<String, ZipArchiveEntry> file : byPath.entrySet()) {
            MessageDigest sha  = Digests.sha256();
            long          size = 0;
            try (InputStream in = aZip.getInputStream(file.getValue())) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    size  += count;
                    total += count;
                    if (total > aMaxBytes) {
                        throw new ArchiveRefusedException("contents exceed maxBytes (" + aMaxBytes + " bytes)");
                    }
                    sha.update(buffer, 0, count);
                }
            } catch (ArchiveRefusedException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                // a corrupt stream may fail unchecked inside the decompressor
                throw new ArchiveRefusedException("entry " + quote(file.getKey()) + " cannot be read");
            }
            String hex = Digests.hex(sha.digest());
            files.add(new FileEntry(file.getKey(), hex, size, file.getValue()));
            hashes.put(file.getKey(), hex);
        }
        return new VersionArchive(aZip, Collections.unmodifiableList(files), Digests.treeDigest(hashes), total);
    }

    private static void checkType(ZipArchiveEntry aEntry, boolean aDirectory) {
        if (aEntry.getPlatform() != ZipArchiveEntry.PLATFORM_UNIX) {
            return;
        }
        if (aEntry.isUnixSymlink()) {
            throw new ArchiveRefusedException("entry " + quote(aEntry.getName()) + " is a symbolic link");
        }
        int type = aEntry.getUnixMode() & UNIX_TYPE_MASK;
        if (type == 0) {
            return;
        }
        if (type != UNIX_REGULAR && type != UNIX_DIRECTORY) {
            throw new ArchiveRefusedException("entry " + quote(aEntry.getName()) + " is not a regular file or directory");
        }
        if ((type == UNIX_DIRECTORY) != aDirectory) {
            throw new ArchiveRefusedException("entry " + quote(aEntry.getName()) + " has a Unix type that does not match its name");
        }
    }

    /** 0x5C cannot occur inside a multi-byte UTF-8 sequence (nor in CP437), so a byte match is a backslash. */
    private static boolean hasBackslash(byte[] aRawName) {
        if (aRawName == null) {
            return false;
        }
        for (byte b : aRawName) {
            if (b == '\\') {
                return true;
            }
        }
        return false;
    }

    /** The path inside the version ({@code ""} for {@code ./}); a refusal for every name it cannot be. */
    static String normalise(String aRaw, boolean aDirectory) {
        for (int i = 0; i < aRaw.length(); i++) {
            char ch = aRaw.charAt(i);
            if (ch == '\\') {
                throw new ArchiveRefusedException("entry " + quote(aRaw) + " has a backslash in its name");
            }
            if (ch < 0x20 || ch == 0x7f) {
                throw new ArchiveRefusedException("entry " + quote(aRaw) + " has a control character in its name");
            }
        }
        if (aRaw.startsWith("/")) {
            throw new ArchiveRefusedException("entry " + quote(aRaw) + " has an absolute name");
        }
        if (aDirectory && aRaw.equals("./")) {
            return "";
        }
        String name = aRaw.startsWith("./") ? aRaw.substring(2) : aRaw;
        if (aDirectory) {
            name = name.substring(0, name.length() - 1);
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new ArchiveRefusedException("entry " + quote(aRaw) + " has an empty, . or .. segment");
            }
        }
        return name;
    }

    private static String quote(String aName) {
        String name = aName.length() > MAX_NAME_IN_REASON ? aName.substring(0, MAX_NAME_IN_REASON) + "..." : aName;
        return "'" + Strings.forLog(name) + "'";
    }

    /** Files sorted as they were found; the digest does not depend on the order. */
    public List<FileEntry> files() {
        return files;
    }

    public String digest() {
        return digest;
    }

    public long totalBytes() {
        return totalBytes;
    }

    /** The contents of a file again (pass 2); the caller recomputes and compares its sha256. */
    public InputStream open(FileEntry aFile) throws IOException {
        return zip.getInputStream(aFile.entry());
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
