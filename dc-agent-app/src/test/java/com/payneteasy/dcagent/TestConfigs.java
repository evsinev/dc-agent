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

    /**
     * The service root of the tests — not the host's /service, which the policy would read (a
     * non-root build user cannot read every service there). JAR fixtures name their serviceDir in it.
     */
    public static final Path SERVICE_ROOT = Path.of("/nonexistent-dc-agent-test/service");

    public static final CommandPathPolicy PATHS = new CommandPathPolicy(ROOTS, List.of(), List.of(SERVICE_ROOT));

    /** {@code <SERVICE_ROOT>/<name>}, for the serviceDir of a JAR/WAR/NODE fixture. */
    public static String serviceDir(String aName) {
        return SERVICE_ROOT.resolve(aName).toString();
    }

    public static final CommandConfigValidator VALIDATOR = new CommandConfigValidator(Gsons.PRETTY_GSON, Duration.ofMinutes(10), PATHS);

    /** How a key is stored in a config: {@code sha256:<hex>}. */
    public static String hash(String aKey) {
        return com.payneteasy.dcagent.core.config.validation.ApiKeyHash.hash(aKey);
    }

    /** With the given agent paths and service roots protected. */
    public static CommandConfigValidator validator(List<Path> aAgentPaths, List<Path> aServiceRoots) {
        return new CommandConfigValidator(Gsons.PRETTY_GSON, Duration.ofMinutes(10),
                new CommandPathPolicy(ROOTS, aAgentPaths, aServiceRoots));
    }

    private TestConfigs() {
    }
}
