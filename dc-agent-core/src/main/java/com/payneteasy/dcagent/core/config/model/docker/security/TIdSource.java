package com.payneteasy.dcagent.core.config.model.docker.security;

/**
 * A {@code securityContext} field that a volume owner may refer to instead of a number.
 */
public enum TIdSource {

    RUN_AS_USER  ( "runAsUser"  ),
    RUN_AS_GROUP ( "runAsGroup" );

    private final String yamlName;

    TIdSource(String aYamlName) {
        yamlName = aYamlName;
    }

    public String yamlName() {
        return yamlName;
    }

    /**
     * @return the source named {@code aName} in dc-docker.yml, or {@code null}
     */
    public static TIdSource fromYaml(String aName) {
        for (TIdSource source : values()) {
            if (source.yamlName.equals(aName)) {
                return source;
            }
        }
        return null;
    }
}
