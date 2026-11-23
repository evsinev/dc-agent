package com.payneteasy.dcagent.systemd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The changes the host preparation makes — each on one named path, never through a link. Behind an
 * interface so tests record them instead. {@link Real}: Java NIO with NOFOLLOW_LINKS / CREATE_NEW, and
 * setfacl / useradd / sysctl by argument array (no shell).
 */
interface HostActions {

    void setfacl(String aSpec, List<Path> aPaths) throws IOException;

    void chown(Path aPath, String aOwner, String aGroup) throws IOException;

    void chmod(Path aPath, String aMode) throws IOException;

    void mkdir(Path aPath, String aOwner, String aMode) throws IOException;

    /** A new file (CREATE_NEW: fails on anything there, a link included). */
    void create(Path aPath, String aOwner, String aMode, String aContent) throws IOException;

    void useradd(String aUser) throws IOException;

    void sysctl(String aAssignment) throws IOException;

    final class Real implements HostActions {

        /** setfacl takes many paths at once; batches keep the given order. */
        private static final int BATCH = 500;

        @Override
        public void setfacl(String aSpec, List<Path> aPaths) throws IOException {
            for (int from = 0; from < aPaths.size(); from += BATCH) {
                List<String> command = new ArrayList<>(List.of("setfacl", "-P", "-m", aSpec, "--"));
                for (Path path : aPaths.subList(from, Math.min(aPaths.size(), from + BATCH))) {
                    command.add(path.toString());
                }
                check(command);
            }
        }

        @Override
        public void chown(Path aPath, String aOwner, String aGroup) throws IOException {
            PosixFileAttributeView view = Files.getFileAttributeView(aPath, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            UserPrincipalLookupService lookup = aPath.getFileSystem().getUserPrincipalLookupService();
            if (aOwner != null) {
                UserPrincipal owner = lookup.lookupPrincipalByName(aOwner);
                view.setOwner(owner);
            }
            if (aGroup != null) {
                GroupPrincipal group = lookup.lookupPrincipalByGroupName(aGroup);
                view.setGroup(group);
            }
        }

        @Override
        public void chmod(Path aPath, String aMode) throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(aPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink()) {
                throw new IOException(aPath + " is a link");
            }
            if (attrs.isOther()) {
                // NOFOLLOW makes the JDK open the path (O_RDONLY) for fchmod: a fifo blocks until a writer comes.
                // chmod(2) by name instead — it is not a link, and only root may change the directory it is in
                Files.setPosixFilePermissions(aPath, PosixFilePermissions.fromString(aMode));
                return;
            }
            Files.getFileAttributeView(aPath, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .setPermissions(PosixFilePermissions.fromString(aMode));
        }

        @Override
        public void mkdir(Path aPath, String aOwner, String aMode) throws IOException {
            Files.createDirectory(aPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(aMode)));
            chmod(aPath, aMode); // umask
            if (aOwner != null) {
                chown(aPath, aOwner, aOwner);
            }
        }

        @Override
        public void create(Path aPath, String aOwner, String aMode, String aContent) throws IOException {
            Set<PosixFilePermission> mode = PosixFilePermissions.fromString(aMode);
            Files.write(Files.createFile(aPath, PosixFilePermissions.asFileAttribute(mode)),
                    aContent.getBytes(java.nio.charset.StandardCharsets.UTF_8), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            chmod(aPath, aMode);
            if (aOwner != null) {
                chown(aPath, aOwner, aOwner);
            }
        }

        @Override
        public void useradd(String aUser) throws IOException {
            check(List.of("useradd", "--system", "--no-create-home", "--home-dir", "/nonexistent", "--shell", "/sbin/nologin", aUser));
        }

        @Override
        public void sysctl(String aAssignment) throws IOException {
            check(List.of("sysctl", "-q", "-w", aAssignment));
        }

        /** 0640 → "rw-r-----". */
        static String symbolic(int aMode) {
            StringBuilder out = new StringBuilder();
            String letters = "rwx";
            for (int bit = 8; bit >= 0; bit--) {
                out.append((aMode & (1 << bit)) != 0 ? letters.charAt((8 - bit) % 3) : '-');
            }
            return out.toString();
        }

        private static void check(List<String> aCommand) throws IOException {
            HostFacts.Real.Result result = HostFacts.Real.execute(aCommand);
            if (result.code() != 0) {
                throw new IOException(aCommand.get(0) + " failed (exit " + result.code() + ")");
            }
        }
    }
}
