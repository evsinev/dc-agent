package com.payneteasy.dcagent.core.config.model;

/** How an entry of a command's {@code keys} authorizes a request. */
public enum TKeyType {

    /** A static key: the request's {@code api-key} (or Basic password) hashed equals {@code hash}. */
    SHA256,

    /** A GitLab CI/CD ID token ({@code Authorization: Bearer}) signed by the issuer, its claims matching a rule. */
    GITLAB_JWT
}
