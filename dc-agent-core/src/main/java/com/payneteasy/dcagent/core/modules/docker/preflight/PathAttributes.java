package com.payneteasy.dcagent.core.modules.docker.preflight;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code lstat} of one path: numeric owner and the raw {@code st_mode} (type, sticky and
 * permission bits). Never follows a symbolic link.
 */
public final class PathAttributes {

    private static final int S_IFMT   = 0170000;
    private static final int S_IFDIR  = 0040000;
    private static final int S_IFLNK  = 0120000;
    private static final int S_ISVTX  = 01000;
    private static final int GROUP_OTHER_WRITE = 0022;

    private final int  uid;
    private final int  gid;
    private final int  mode;
    private final long device;
    private final long inode;

    public PathAttributes(int aUid, int aGid, int aMode, long aDevice, long aInode) {
        uid    = aUid;
        gid    = aGid;
        mode   = aMode;
        device = aDevice;
        inode  = aInode;
    }

    /** Reads attributes with {@code lstat}; the source of attributes can be replaced in tests. */
    @FunctionalInterface
    public interface Reader {

        /**
         * @return attributes, or {@code null} when nothing exists at the path (a dangling link exists)
         */
        PathAttributes read(Path aPath) throws IOException;
    }

    public static final Reader LSTAT = aPath -> {
        try {
            Map<String, Object> attributes = Files.readAttributes(aPath, "unix:uid,gid,mode,dev,ino", LinkOption.NOFOLLOW_LINKS);
            return new PathAttributes(
                      (Integer) attributes.get("uid")
                    , (Integer) attributes.get("gid")
                    , (Integer) attributes.get("mode")
                    , (Long)    attributes.get("dev")
                    , (Long)    attributes.get("ino")
            );
        } catch (NoSuchFileException e) {
            return null;
        }
    };

    public int uid() {
        return uid;
    }

    public int gid() {
        return gid;
    }

    /** Permission bits only, e.g. {@code 0755}. */
    public int permissions() {
        return mode & 0777;
    }

    public boolean isDirectory() {
        return (mode & S_IFMT) == S_IFDIR;
    }

    public boolean isSymbolicLink() {
        return (mode & S_IFMT) == S_IFLNK;
    }

    public boolean isSticky() {
        return (mode & S_ISVTX) != 0;
    }

    /**
     * The same file system object (device and inode) — also through a bind mount, which
     * resolving symbolic links does not reveal.
     */
    public boolean isSameFile(PathAttributes aOther) {
        return aOther != null && device == aOther.device && inode == aOther.inode;
    }

    public boolean isGroupOrOtherWritable() {
        return (mode & GROUP_OTHER_WRITE) != 0;
    }
}
