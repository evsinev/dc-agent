package com.payneteasy.dcagent.cli.config;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TCliConfig {

    String baseUrl;
    String consumerKey;

    String caCertPath          = "ca.crt";
    String clientCertPath       = "client.crt";
    String clientPrivateKeyPath = "client.key";

}
