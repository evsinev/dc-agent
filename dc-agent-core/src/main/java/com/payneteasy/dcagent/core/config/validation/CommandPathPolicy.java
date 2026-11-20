package com.payneteasy.dcagent.core.config.validation;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where a command may write. A path from a command config ({@code dir}, the directory of
 * {@code jarFilename}/{@code warFilename}) must
 * <ol>
 *     <li>be absolute, without {@code .} or {@code ..} segments — the path checked is then the path
 *     used ({@code ZipArchiveVersionSettings} normalises before links are resolved);</li>
 *     <li>resolve ({@link PathResolver}) strictly inside one of the {@code COMMAND_DIRS} roots — an
 *     allow-list: a forgotten system directory cannot be reached;</li>
 *     <li>not overlap — be, contain or lie inside — a protected path: the agent's home, CONFIG_DIR,
 *     the agent jar, {@code SERVICES_DIR}, {@code SERVICES_DEFINITION_DIR}, each service there and
 *     what daemontools runs from it ({@code run}, {@code finish}, {@code log}, {@code log/run},
 *     {@code log/finish}, {@code env/*}, {@code log/env/*}). Every protected path counts with every
 *     link on its way, so a command cannot replace a link the agent or a service reads through.</li>
 * </ol>
 * The protected set is computed on every check — at start, in {@code check-config}, before the control
 * plane writes and on every call — because services and links change while the agent runs.
 */
public class CommandPathPolicy {

    private static final Pattern SEPARATORS = Pattern.compile("[,\\s]+");

    private static final String[] SERVICE_FILES = {"run", "finish", "log", "log/run", "log/finish"};
    private static final String[] SERVICE_ENVS  = {"env", "log/env"};

    private final List<Path> roots;
    private final List<Path> agentPaths;
    private final List<Path> serviceRoots;

    /**
     * @param aRoots        {@code COMMAND_DIRS}, already parsed ({@link #parseCommandDirs})
     * @param aAgentPaths   the agent's home, CONFIG_DIR, the jar as started and as loaded — the
     *                      home's top-level entries are protected too
     * @param aServiceRoots {@code SERVICES_DIR}, {@code SERVICES_DEFINITION_DIR}
     */
    public CommandPathPolicy(List<Path> aRoots, List<Path> aAgentPaths, List<Path> aServiceRoots) {
        roots        = List.copyOf(aRoots);
        agentPaths   = List.copyOf(aAgentPaths);
        serviceRoots = List.copyOf(aServiceRoots);
    }

    /**
     * {@code COMMAND_DIRS}: absolute paths separated by commas and/or whitespace ({@code "/opt,/srv"},
     * {@code "/opt, /srv"}, {@code "/opt /srv"}); a path with a space is not supported.
     *
     * @throws IllegalArgumentException empty, a relative path, {@code /} or {@code .}/{@code ..} in a path
     */
    public static List<Path> parseCommandDirs(String aValue) {
        List<Path> roots = new ArrayList<>();
        for (String part : SEPARATORS.split(aValue == null ? "" : aValue.trim())) {
            if (part.isEmpty()) {
                continue;
            }
            Path path;
            try {
                path = Path.of(part);
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException("COMMAND_DIRS: not a valid path: " + part);
            }
            if (!path.isAbsolute() || hasDotSegment(path)) {
                throw new IllegalArgumentException("COMMAND_DIRS: must be absolute without . or ..: " + part);
            }
            if (path.getNameCount() == 0) {
                throw new IllegalArgumentException("COMMAND_DIRS: / is not allowed");
            }
            roots.add(path);
        }
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("COMMAND_DIRS: no directory given");
        }
        return roots;
    }

    /** The problems of {@code aValue} as the directory a command writes into, named by {@code aField}. */
    public List<String> checkDirectory(String aField, String aValue) {
        List<String> problems = new ArrayList<>();
        if (aValue == null || aValue.isBlank()) {
            problems.add("\"" + aField + "\" is required");
            return problems;
        }
        Path path;
        try {
            path = Path.of(aValue);
        } catch (InvalidPathException e) {
            problems.add("\"" + aField + "\" is not a valid path");
            return problems;
        }
        if (!path.isAbsolute() || hasDotSegment(path) || !path.equals(path.normalize())) {
            problems.add("\"" + aField + "\" must be an absolute path without . or .. segments");
            return problems;
        }

        Path resolved;
        try {
            resolved = PathResolver.resolve(path, new ArrayList<>());
        } catch (IOException e) {
            problems.add("\"" + aField + "\" cannot be resolved (" + e.getMessage() + ")");
            return problems;
        }

        boolean inside = false;
        for (Path root : roots) {
            Path resolvedRoot;
            try {
                resolvedRoot = PathResolver.resolve(root, new ArrayList<>());
            } catch (IOException e) {
                problems.add("COMMAND_DIRS " + root + " cannot be resolved (" + e.getMessage() + ")");
                continue;
            }
            if (resolved.startsWith(resolvedRoot) && !resolved.equals(resolvedRoot)) {
                inside = true;
            }
        }
        if (!inside) {
            problems.add("\"" + aField + "\" " + path + " must lie inside one of COMMAND_DIRS " + roots
                    + (resolved.equals(path) ? "" : " (it resolves to " + resolved + ")"));
            return problems;
        }

        Set<Path> protectedPaths;
        try {
            protectedPaths = protectedPaths();
        } catch (IOException e) {
            problems.add("the protected paths cannot be resolved (" + e.getMessage() + ")");
            return problems;
        }
        for (Path protectedPath : protectedPaths) {
            if (resolved.startsWith(protectedPath) || protectedPath.startsWith(resolved)) {
                problems.add("\"" + aField + "\" " + path + " overlaps " + protectedPath
                        + ", which the agent or a service needs untouched");
                return problems;
            }
        }
        return problems;
    }

    /**
     * {@code serviceDir} of a JAR/WAR/NODE command: a direct child of {@code SERVICES_DIR} or
     * {@code SERVICES_DEFINITION_DIR}, so the service it restarts is one this policy protects.
     */
    public List<String> checkServiceDir(String aServiceDir) {
        Path path;
        try {
            path = Path.of(aServiceDir);
        } catch (InvalidPathException e) {
            return List.of("\"serviceDir\" is not a valid path");
        }
        Path parent = path.getParent();
        Path name   = path.getFileName();
        if (path.isAbsolute() && !hasDotSegment(path) && parent != null && name != null
                && serviceRoots.contains(parent) && com.payneteasy.dcagent.core.config.CommandNames.isValid(name.toString())) {
            return List.of();
        }
        return List.of("\"serviceDir\" " + aServiceDir + " must be a service directly in one of " + serviceRoots
                + " (with no serviceDir: /service/<serviceName>)");
    }

    /** The agent's paths and everything daemontools runs, each with every link on its way. */
    Set<Path> protectedPaths() throws IOException {
        Set<Path> protectedPaths = new LinkedHashSet<>();
        for (Path agentPath : agentPaths) {
            protect(agentPath, protectedPaths);
        }
        if (!agentPaths.isEmpty()) {
            // the home's own entries (the jar, config, anything there), links included
            for (Path entry : list(agentPaths.get(0))) {
                protect(entry, protectedPaths);
            }
        }
        for (Path serviceRoot : serviceRoots) {
            protect(serviceRoot, protectedPaths);
            for (Path service : list(serviceRoot)) {
                protect(service, protectedPaths);
                if (!Files.isDirectory(service)) {
                    continue; // a file there is not a service (it is protected itself all the same)
                }
                for (String file : SERVICE_FILES) {
                    protect(service.resolve(file), protectedPaths);
                }
                for (String env : SERVICE_ENVS) {
                    // the directory itself first, links included: an empty (or missing) env behind a
                    // link would otherwise be free to fill with LD_PRELOAD
                    protect(service.resolve(env), protectedPaths);
                    for (Path variable : list(service.resolve(env))) {
                        protect(variable, protectedPaths);
                    }
                }
            }
        }
        return protectedPaths;
    }

    private static void protect(Path aPath, Set<Path> aProtected) throws IOException {
        List<Path> links = new ArrayList<>();
        Path resolved = PathResolver.resolve(aPath.toAbsolutePath(), links);
        for (Path link : links) {
            aProtected.add(link);
        }
        aProtected.add(resolved);
    }

    /** The entries of a directory reached through links; none when it is missing or not a directory. */
    private static List<Path> list(Path aDir) throws IOException {
        if (!Files.isDirectory(aDir)) {
            return List.of();
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(aDir)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        }
        return entries;
    }

    static boolean hasDotSegment(Path aPath) {
        for (Path name : aPath) {
            String value = name.toString();
            if (".".equals(value) || "..".equals(value)) {
                return true;
            }
        }
        return false;
    }
}
