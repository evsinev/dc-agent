package com.payneteasy.dcagent.cli.config.impl;

import com.payneteasy.dcagent.cli.config.CliConfiguration;
import com.payneteasy.dcagent.cli.config.ICliConfigReaderService;
import org.junit.Test;

import java.io.File;

import static com.google.common.truth.Truth.assertThat;


public class CliConfigReaderServiceImplTest {

    @Test
    public void read_config() {
        ICliConfigReaderService configReader = new CliConfigReaderServiceImpl(new File("src/test/resources/dc-agent-test"));
        CliConfiguration        config       = configReader.readConfig();
        assertThat(config).isNotNull();
    }
}