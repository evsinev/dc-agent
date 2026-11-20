package com.payneteasy.dcagent.core.config.validation;

import java.util.List;

/**
 * A command config the agent refuses to use. The message lists the problems; it never carries a
 * secret (an api key, a header value) — only file names, field paths and the expected form.
 */
public class ConfigProblemException extends IllegalStateException {

    private final List<String> problems;

    public ConfigProblemException(List<String> aProblems) {
        super(String.join("; ", aProblems));
        problems = List.copyOf(aProblems);
    }

    public ConfigProblemException(String aProblem) {
        this(List.of(aProblem));
    }

    public List<String> getProblems() {
        return problems;
    }
}
