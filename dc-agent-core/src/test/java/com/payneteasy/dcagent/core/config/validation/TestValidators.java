package com.payneteasy.dcagent.core.config.validation;

import com.google.gson.Gson;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** A validator for tests: commands may write in /opt, /srv and the temporary directory; nothing is protected. */
public final class TestValidators {

    public static final Path TMP = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();

    private TestValidators() {
    }

    public static CommandPathPolicy permissivePaths() {
        return new CommandPathPolicy(List.of(Path.of("/opt"), Path.of("/srv"), TMP), List.of(), List.of());
    }

    public static CommandConfigValidator validator(Gson aGson) {
        return new CommandConfigValidator(aGson, Duration.ofMinutes(10), permissivePaths());
    }
}
