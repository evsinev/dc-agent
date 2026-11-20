package com.payneteasy.dcagent.core.config.validation;

/**
 * How a plaintext key in {@code apiKeys} counts — the only rule the modes differ in. An entry with
 * the reserved {@code sha256:} prefix that is not a well-formed hash is a problem in every mode.
 */
public enum ConfigCheckMode {

    /** The files as found at start and in {@code check-config}: a plaintext key is about to be hashed. */
    PRE_MIGRATION,

    /** After the migration and before the control plane writes: a plaintext key is a problem. */
    STRICT,

    /**
     * On a call: a plaintext key is not a problem of the config — it simply never matches (logged);
     * the hashed keys of the same config keep working.
     */
    REQUEST
}
