package com.payneteasy.dcagent;

import com.payneteasy.dcagent.core.config.validation.CommandConfigValidator;
import com.payneteasy.dcagent.core.config.validation.CommandPathPolicy;
import com.payneteasy.dcagent.core.util.gson.Gsons;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The command config validator the agent builds at start, for tests that build services by hand:
 * commands may write in /opt, /srv and the temporary directory; nothing is protected.
 */
public final class TestConfigs {

    public static final List<Path> ROOTS = List.of(Path.of("/opt"), Path.of("/srv"),
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath());

    /** /service as the service root, so the JAR default serviceDir /service/<serviceName> is valid. */
    public static final CommandPathPolicy PATHS = new CommandPathPolicy(ROOTS, List.of(), List.of(Path.of("/service")));

    public static final CommandConfigValidator VALIDATOR = new CommandConfigValidator(Gsons.PRETTY_GSON, Duration.ofMinutes(10), PATHS);

    /** With the given agent paths and service roots protected. */
    public static CommandConfigValidator validator(List<Path> aAgentPaths, List<Path> aServiceRoots) {
        return new CommandConfigValidator(Gsons.PRETTY_GSON, Duration.ofMinutes(10),
                new CommandPathPolicy(ROOTS, aAgentPaths, aServiceRoots));
    }

    private TestConfigs() {
    }
}
