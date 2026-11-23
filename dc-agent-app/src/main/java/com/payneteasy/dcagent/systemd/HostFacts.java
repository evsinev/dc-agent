package com.payneteasy.dcagent.systemd;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * What the host preparation needs to know about a path — read-only. Behind an interface so the decisions
 * of {@link HostPreparation} can be tested on invented hosts (root-owned trees, ACLs, hard links) without
 * root. {@link Real} asks the file system, getfacl and, for access as the agent, runuser.
 */
interface HostFacts {

    enum Type { DIR, FILE, FIFO, LINK, OTHER }

    /** Never following a link: the path itself. */
    record Attrs(Type type, String owner, String group, int mode, long links, long device) {
        boolean writableByGroupOrOthers() {
            return (mode & 0022) != 0;
        }
    }

    Optional<Attrs> attrs(Path aPath);

    /** The extended ACL entries of the path (named users/groups, mask, defaults); empty — none. */
    List<String> acl(Path aPath);

    /** Paths in the tree (this file system) that have an extended ACL — empty when none. */
    List<String> aclsInTree(Path aDir);

    /** Every entry of the tree, post-order (children before their directory), not following links; null — not fully walked. */
    List<Path> walk(Path aDir);

    /** A mount point strictly inside the directory, or null. */
    String mountInside(Path aDir);

    /** {@code test <flags> path} as the agent (e.g. "-r", "-x"). */
    boolean agentCan(Path aPath, String... aFlags);

    /** The first obstacle for the agent to create, rename and delete in the whole tree, or null. */
    String agentObstacle(Path aDir);

    boolean userExists(String aUser);

    /** Lines of a small text file (the env file); empty when unreadable. */
    List<String> lines(Path aFile);

    /** The real host. */
    final class Real implements HostFacts {

        private static final String AGENT = HostPreparation.USER;

        @Override
        public Optional<Attrs> attrs(Path aPath) {
            try {
                BasicFileAttributes basic = Files.readAttributes(aPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                Type type = basic.isSymbolicLink() ? Type.LINK : basic.isDirectory() ? Type.DIR : basic.isRegularFile() ? Type.FILE
                        : com.payneteasy.dcagent.core.daemontools.Supervise.isFifo(aPath) ? Type.FIFO : Type.OTHER;
                PosixFileAttributes posix = Files.readAttributes(aPath, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                Map<String, Object> unix = Files.readAttributes(aPath, "unix:mode,nlink,dev", LinkOption.NOFOLLOW_LINKS);
                return Optional.of(new Attrs(type, posix.owner().getName(), posix.group().getName(), ((Number) unix.get("mode")).intValue() & 07777,
                        ((Number) unix.get("nlink")).longValue(), ((Number) unix.get("dev")).longValue()));
            } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        /** What getfacl cannot tell is not "no ACL": an entry no rule accepts, so nothing is changed there. */
        static final String UNKNOWN = "unknown:(getfacl failed):rwx";

        @Override
        public List<String> acl(Path aPath) {
            Result result = execute(List.of("getfacl", "-cp", "--", aPath.toString()));
            return result.code() == 0 ? extended(result.lines()) : List.of(UNKNOWN);
        }

        @Override
        public List<String> aclsInTree(Path aDir) {
            Result result = execute(List.of("getfacl", "-cpR", "--skip-base", "-P", "--", aDir.toString()));
            return result.code() == 0 ? extended(result.lines()) : List.of(UNKNOWN);
        }

        private static List<String> extended(List<String> aLines) {
            List<String> entries = new ArrayList<>();
            for (String line : aLines) {
                if (!line.isEmpty() && !line.startsWith("#") && !line.matches("(user|group|other)::.*")) {
                    entries.add(line);
                }
            }
            return entries;
        }

        @Override
        public List<Path> walk(Path aDir) {
            List<Path> paths = new ArrayList<>();
            try {
                Files.walkFileTree(aDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path aFile, BasicFileAttributes aAttrs) {
                        paths.add(aFile);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path aFile, IOException aError) throws IOException {
                        throw aError;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path aDirectory, IOException aError) throws IOException {
                        if (aError != null) {
                            throw aError;
                        }
                        paths.add(aDirectory);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                return null; // unknown
            }
            return paths;
        }

        @Override
        public String mountInside(Path aDir) {
            return com.payneteasy.dcagent.core.config.validation.CommandPathPolicy.mountInside(aDir);
        }

        @Override
        public boolean agentCan(Path aPath, String... aFlags) {
            List<String> command = new ArrayList<>(List.of("runuser", "-u", AGENT, "--", "test"));
            for (int i = 0; i < aFlags.length; i++) {
                if (i > 0) {
                    command.add("-a");
                }
                command.add(aFlags[i]);
                command.add(aPath.toString());
            }
            return exit(command) == 0;
        }

        @Override
        public String agentObstacle(Path aDir) {
            String dir = aDir.toString();
            List<List<String>> checks = List.of(
                    List.of("find", dir, "-xdev", "-type", "d", "(", "!", "-readable", "-o", "!", "-writable", "-o", "!", "-executable", ")", "-print", "-quit"),
                    List.of("find", dir, "-xdev", "-type", "f", "-name", ".dc-agent.lock", "!", "-writable", "-print", "-quit"),
                    List.of("find", dir, "-xdev", "-type", "f", "!", "-readable", "-print", "-quit"));
            for (List<String> check : checks) {
                List<String> command = new ArrayList<>(List.of("runuser", "-u", AGENT, "--"));
                command.addAll(check);
                Result result = execute(command);
                if (result.code != 0) {
                    return "cannot walk " + aDir + " as " + AGENT;
                }
                if (!result.lines.isEmpty()) {
                    return result.lines.get(0);
                }
            }
            // a sticky directory: every entry must be the agent's (else rename/delete fail)
            List<Path> tree = walk(aDir);
            if (tree == null) {
                return "cannot walk " + aDir + " completely";
            }
            for (Path path : tree) {
                Optional<Attrs> attrs = attrs(path);
                if (attrs.isPresent() && attrs.get().type() == Type.DIR && (attrs.get().mode() & 01000) != 0) {
                    try (var entries = Files.list(path)) {
                        for (Path entry : (Iterable<Path>) entries::iterator) {
                            if (attrs(entry).map(a -> !AGENT.equals(a.owner())).orElse(true)) {
                                return entry + " (in a sticky directory, not " + AGENT + "'s)";
                            }
                        }
                    } catch (IOException e) {
                        return path + " cannot be listed";
                    }
                }
            }
            return null;
        }

        @Override
        public boolean userExists(String aUser) {
            return exit(List.of("id", "-u", aUser)) == 0;
        }

        @Override
        public List<String> lines(Path aFile) {
            try {
                return Files.readAllLines(aFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return List.of();
            }
        }

        record Result(int code, List<String> lines) {
        }

        static List<String> run(List<String> aCommand) {
            return execute(aCommand).lines;
        }

        static int exit(List<String> aCommand) {
            return execute(aCommand).code;
        }

        /** An external tool, by argument array (no shell), from / (the agent may not enter root's home). */
        static Result execute(List<String> aCommand) {
            try {
                Process process = new ProcessBuilder(aCommand).directory(new File("/")).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                process.getOutputStream().close();
                String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!process.waitFor(10, TimeUnit.MINUTES)) {
                    process.destroyForcibly();
                    return new Result(-1, List.of());
                }
                List<String> lines = out.isEmpty() ? List.of() : List.of(out.split("\n"));
                return new Result(process.exitValue(), lines);
            } catch (IOException e) {
                return new Result(-1, List.of());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(-1, List.of());
            }
        }
    }
}
