package com.payneteasy.dcagent.jetty;

import com.payneteasy.dcagent.core.config.CommandNames;
import com.payneteasy.dcagent.core.config.model.IApiKeys;
import com.payneteasy.dcagent.core.config.model.IGetTaskType;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.exception.WrongApiKeyException;
import com.payneteasy.dcagent.core.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.function.Function;

/**
 * The authorization step every task endpoint runs first — before reading the body or touching the
 * disk: a key is present → the name is valid ({@link CommandNames}) → the config loads → its
 * {@code type} matches (a missing type is allowed with a warning) → the key is in its apiKeys.
 * Every refusal is the same {@link WrongApiKeyException} ({@link CheckApiKey#UNAUTHORIZED}); the
 * reason — step, name, loader exception type — goes to the agent log only (never a key, never a
 * loader exception message).
 */
public class CommandAuth {

    private static final Logger LOG = LoggerFactory.getLogger(CommandAuth.class);

    private final CheckApiKey checkApiKey = new CheckApiKey();

    public <T extends IApiKeys & IGetTaskType> T authorize(HttpServletRequest aRequest, String aName,
                                                           TaskType aExpected, Function<String, T> aLoader) {
        if (Strings.isEmpty(checkApiKey.findKey(aRequest))) {
            throw reject("no api-key/Authorization Basic key in the request", aName, aExpected);
        }

        if (!CommandNames.isValid(aName)) {
            throw reject("invalid command name", aName, aExpected);
        }

        T config;
        try {
            config = aLoader.apply(aName);
        } catch (RuntimeException e) {
            // Exception types only: a parser message may quote the config, api keys included
            // (Gson "duplicate key: <secret>", a YAML snippet).
            throw reject("cannot load config (" + typeChain(e) + "), check " + aName + ".json/.yml in CONFIG_DIR",
                    aName, aExpected);
        }
        if (config == null) {
            throw reject("empty config", aName, aExpected);
        }

        TaskType type = config.getType();
        if (type == null) {
            LOG.warn("Command {} has no type in its config, expected {}; add \"type\": \"{}\"",
                    Strings.forLog(aName), aExpected, aExpected);
        } else if (type != aExpected) {
            throw reject("type " + type + ", expected " + aExpected, aName, aExpected);
        }

        String keyProblem = checkApiKey.rejectReason(aRequest, config);
        if (keyProblem != null) {
            throw reject(keyProblem, aName, aExpected);
        }
        return config;
    }

    /** {@code IllegalStateException <- JsonSyntaxException}: the cause chain as class names, no messages. */
    static String typeChain(Throwable aError) {
        StringBuilder sb    = new StringBuilder();
        Throwable     error = aError;
        for (int i = 0; i < 10 && error != null; i++) {
            if (i > 0) {
                sb.append(" <- ");
            }
            sb.append(error.getClass().getSimpleName());
            error = error.getCause();
        }
        return sb.toString();
    }

    private static WrongApiKeyException reject(String aReason, String aName, TaskType aExpected) {
        LOG.warn("Rejected request for command {} ({}): {}", Strings.forLog(aName), aExpected, aReason);
        return new WrongApiKeyException(CheckApiKey.UNAUTHORIZED);
    }
}
