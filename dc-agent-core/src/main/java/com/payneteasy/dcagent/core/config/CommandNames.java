package com.payneteasy.dcagent.core.config;

import java.util.regex.Pattern;

/**
 * The one rule for a command name (the config file name in CONFIG_DIR without extension): letters,
 * digits, {@code . _ -} and no {@code ..}; no length limit. Used when a command is written (the
 * operator, the agent's control plane) and when it is called (the agent's task endpoints), so a
 * command that could be created can always be called.
 */
public final class CommandNames {

    private static final Pattern NAME = Pattern.compile("^[0-9a-zA-Z._-]+$");

    private CommandNames() {
    }

    public static boolean isValid(String aName) {
        return aName != null && !aName.contains("..") && NAME.matcher(aName).matches();
    }
}
