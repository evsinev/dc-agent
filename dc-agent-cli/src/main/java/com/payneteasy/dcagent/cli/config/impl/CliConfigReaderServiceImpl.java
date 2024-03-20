package com.payneteasy.dcagent.cli.config.impl;

import com.payneteasy.dcagent.cli.config.CliConfiguration;
import com.payneteasy.dcagent.cli.config.ICliConfigReaderService;
import com.payneteasy.dcagent.cli.config.TCliConfig;
import com.payneteasy.dcagent.core.util.SecureKeys;
import com.payneteasy.dcagent.core.util.Strings;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static java.util.Collections.singletonList;

public class CliConfigReaderServiceImpl implements ICliConfigReaderService {

    private static final Logger LOG = LoggerFactory.getLogger( CliConfigReaderServiceImpl.class );

    private final File       baseDir;
    private final File       configDir;
    private final YamlParser yamlParser = new YamlParser();
    private final SecureKeys secureKeys = new SecureKeys();

    public CliConfigReaderServiceImpl(File aBaseDir) {
        baseDir   = aBaseDir;
        configDir = new File(aBaseDir, "config");
    }

    @Override
    public CliConfiguration readConfig() {
        File configFile = configFile("config.yaml");
        LOG.debug("Loading config from {}", configFile.getAbsolutePath());

        TCliConfig config               = yamlParser.parseFile(configFile, TCliConfig.class);
        File       clientCertficateFile = configFile(config.getClientCertPath());

        return CliConfiguration.builder()
                .consumerKey            ( config.getConsumerKey() )
                .clientCertificate      ( secureKeys.loadCertificate(clientCertficateFile))
                .caCertificate          ( secureKeys.loadCertificate(configFile(config.getCaCertPath())))
                .clientPrivateKey       ( secureKeys.loadPrivateKeyFile(configFile(config.getClientPrivateKeyPath())))
                .clientCertificateFile  ( clientCertficateFile    )
                .baseDir                ( baseDir                 )
                .baseUrl                ( config.getBaseUrl()     )
                .openUrlCommand         ( getTextDefault(config.getOpenUrlCommand(), "open") )
                .openUrlCommandArgs     ( getCommandArgs(config.getOpenUrlCommandArgs()))
                .build();
    }

    private List<String> getCommandArgs(List<String> aArgs) {
        return aArgs != null && !aArgs.isEmpty() ? aArgs : singletonList("$url");
    }

    private String getTextDefault(String openUrlCommand, String open) {
        return Strings.hasText(openUrlCommand) ? openUrlCommand : open;
    }

    private File configFile(String aFilename) {
        return new File(configDir, aFilename);
    }
}
