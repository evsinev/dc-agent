package com.payneteasy.dcagent.core.config.model;

import java.util.List;
import java.util.Map;

/** The keys of a command config: the legacy {@code apiKeys} map or {@code keys} — never both. */
public interface IApiKeys {

    /** Legacy: {@code sha256:<hex> -> owner}. */
    Map<String, String> getApiKeys();

    /** Typed keys (static SHA-256 and GitLab ID tokens); null when the config uses apiKeys. */
    default List<TCommandKey> getKeys() {
        return null;
    }
}
