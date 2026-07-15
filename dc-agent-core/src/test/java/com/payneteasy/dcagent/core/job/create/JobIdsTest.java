package com.payneteasy.dcagent.core.job.create;

import org.junit.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

public class JobIdsTest {

    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    @Test
    public void job_id_decodes_to_24_bytes() {
        assertThat(URL_DECODER.decode(JobIds.createJobId())).hasSize(24);
    }

    @Test
    public void job_id_is_unique_across_calls() {
        assertThat(JobIds.createJobId()).isNotEqualTo(JobIds.createJobId());
    }

    @Test
    public void time_job_id_decodes_to_16_bytes() {
        assertThat(URL_DECODER.decode(JobIds.createJobIdTime())).hasSize(16);
    }

    @Test
    public void time_job_id_is_unique_across_calls() {
        assertThat(JobIds.createJobIdTime()).isNotEqualTo(JobIds.createJobIdTime());
    }
}
