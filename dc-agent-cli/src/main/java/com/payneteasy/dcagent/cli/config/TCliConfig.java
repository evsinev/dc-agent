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

    String caCertPath;
    String clientCertPath;
    String clientKeyPath;
    
}
