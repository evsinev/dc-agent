package com.payneteasy.dcagent.core.modules.docker.resolver;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * The {@code keep} globs of a MIRROR {@code dirConfig}: Java {@code glob:} syntax, matched against
 * a path relative to {@code source} with {@code /} separators — {@code *} stays inside one
 * component, {@code **} crosses them. Each pattern must be a plain relative path: no leading
 * {@code /}, no {@code .} / {@code ..} / empty component, no backslash, no trailing {@code /}.
 */
public final class KeepPatterns {

    private static final KeepPatterns NONE = new KeepPatterns(List.of());

    private final List<PathMatcher> matchers;

    private KeepPatterns(List<PathMatcher> aMatchers) {
        matchers = List.copyOf(aMatchers);
    }

    public static KeepPatterns none() {
        return NONE;
    }

    /**
     * @param aPath e.g. {@code volumes[0].dirConfig.keep} for the messages
     * @throws IllegalStateException with every invalid pattern
     */
    public static KeepPatterns compile(List<String> aPatterns, String aPath) {
        if (aPatterns == null || aPatterns.isEmpty()) {
            return NONE;
        }
        List<PathMatcher> compiled = new ArrayList<>(aPatterns.size());
        List<String>      errors   = new ArrayList<>();
        for (int i = 0; i < aPatterns.size(); i++) {
            String pattern = aPatterns.get(i);
            String where   = aPath + "[" + i + "]";
            String problem = shapeProblem(pattern);
            if (problem != null) {
                errors.add(where + " ('" + pattern + "'): " + problem);
                continue;
            }
            try {
                compiled.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
            } catch (PatternSyntaxException e) {
                errors.add(where + " ('" + pattern + "'): invalid glob: " + e.getDescription());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(String.join("\n", errors));
        }
        return new KeepPatterns(compiled);
    }

    private static String shapeProblem(String aPattern) {
        if (aPattern == null || aPattern.isEmpty()) {
            return "must not be empty";
        }
        if (aPattern.startsWith("/")) {
            return "must be relative to source (no leading '/')";
        }
        if (aPattern.endsWith("/")) {
            return "must not end with '/' (a directory pattern keeps its whole subtree)";
        }
        if (aPattern.indexOf('\\') >= 0) {
            return "backslash is not allowed";
        }
        for (String component : aPattern.split("/", -1)) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                return "'.', '..' and empty components are not allowed";
            }
        }
        return null;
    }

    /** @param aRelative a path relative to {@code source}, never empty */
    public boolean matches(Path aRelative) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(aRelative)) {
                return true;
            }
        }
        return false;
    }
}
