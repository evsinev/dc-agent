package com.payneteasy.dcagent.core.modules.docker.runtime;

import java.util.Locale;

/**
 * What the {@code docker} command of the run script really is on this host. It only changes how a
 * {@code securityContext.passwdEntry} is applied: podman has {@code --passwd-entry}, docker does not.
 * The run script calls {@code docker} either way (podman hosts use the podman-docker wrapper), so a
 * config without passwdEntry gets the same run script in both modes.
 */
public enum ContainerRuntime {

    PODMAN, DOCKER;

    /** Startup parameter {@code CONTAINER_RUNTIME}: {@code podman} or {@code docker}. */
    public static ContainerRuntime parse(String aValue) {
        String value = aValue == null ? "" : aValue.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "podman":
                return PODMAN;
            case "docker":
                return DOCKER;
            default:
                throw new IllegalArgumentException("CONTAINER_RUNTIME must be 'podman' or 'docker', got '" + aValue + "'");
        }
    }

    /** Whether {@code docker --version} output belongs to this runtime. */
    public boolean matchesVersionOutput(String aVersionOutput) {
        boolean podman = aVersionOutput != null && aVersionOutput.toLowerCase(Locale.ROOT).contains("podman");
        return this == PODMAN ? podman : !podman;
    }

    public String parameterValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
