package com.payneteasy.dcagent.admin.service.daemontools.impl;

import com.payneteasy.dcagent.admin.service.daemontools.model.SuperviseStatusFile;
import com.payneteasy.dcagent.admin.service.daemontools.model.WantStateType;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

import static com.payneteasy.tlv.HexUtil.toFormattedHexString;
import static org.assertj.core.api.Assertions.assertThat;

public class SuperviseStatusFileParserTest {

    private static final Logger LOG = LoggerFactory.getLogger( SuperviseStatusFileParserTest.class );

    private static final Base64.Decoder BASE64 = Base64.getDecoder();

    private final SuperviseStatusFileParser parser = new SuperviseStatusFileParser();

    @Test
    public void parse_running() {
        byte[]        bytes  = BASE64.decode("QAAAAGYIBxIU6akcVRoAAAB1");
        LOG.debug("Parsing {}", toFormattedHexString(bytes));

        SuperviseStatusFile status = parser.parseStatusFile(bytes);
        System.out.println("status = " + status);

        assertThat(status).isNotNull();
        assertThat(status.getPid()).isEqualTo(6741);
        assertThat(status.getWant()).isEqualTo(WantStateType.WANT_UP);
        assertThat(status.isPaused()).isFalse();
    }
}