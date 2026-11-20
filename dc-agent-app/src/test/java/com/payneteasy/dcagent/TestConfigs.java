package com.payneteasy.dcagent;

import com.payneteasy.dcagent.core.config.validation.CommandConfigValidator;
import com.payneteasy.dcagent.core.util.gson.Gsons;

import java.time.Duration;

/** The command config validator the agent builds at start, for tests that build services by hand. */
public final class TestConfigs {

    public static final CommandConfigValidator VALIDATOR = new CommandConfigValidator(Gsons.PRETTY_GSON, Duration.ofMinutes(10));

    private TestConfigs() {
    }
}
