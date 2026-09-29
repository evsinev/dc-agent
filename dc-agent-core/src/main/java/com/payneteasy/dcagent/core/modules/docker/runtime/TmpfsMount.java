package com.payneteasy.dcagent.core.modules.docker.runtime;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/** One {@code securityContext.tmpfs} entry: {@code /path} or {@code /path:options} → {@code --tmpfs}. */
public final class TmpfsMount {

    private static final Pattern PATH    = Pattern.compile("/[A-Za-z0-9._/-]*");
    private static final Pattern OPTIONS = Pattern.compile("[a-z0-9=,]+");

    private final String path;
    private final String options;

    private TmpfsMount(String aPath, String aOptions) {
        path    = aPath;
        options = aOptions;
    }

    public static TmpfsMount parse(String aSpec) {
        int    colon   = aSpec.indexOf(':');
        String path    = colon < 0 ? aSpec : aSpec.substring(0, colon);
        String options = colon < 0 ? null : aSpec.substring(colon + 1);

        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("'" + aSpec + "': the path must be absolute, letters, digits and . _ / - only");
        }
        for (Path name : Paths.get(path)) {
            if (name.toString().equals(".") || name.toString().equals("..")) {
                throw new IllegalArgumentException("'" + aSpec + "': '.' and '..' are not allowed");
            }
        }
        if (options != null && !OPTIONS.matcher(options).matches()) {
            throw new IllegalArgumentException("'" + aSpec + "': options must be like rw,size=64m (a-z 0-9 = , only)");
        }
        return new TmpfsMount(Paths.get(path).normalize().toString(), options);
    }

    /** Normalized absolute path. */
    public String path() {
        return path;
    }

    /** The {@code --tmpfs} value. */
    public String argument() {
        return options == null ? path : path + ":" + options;
    }
}
