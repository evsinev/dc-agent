package com.payneteasy.dcagent.operator.service.services.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStateType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStatus;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.services.model.HostServiceItem;
import com.payneteasy.dcagent.operator.service.services.model.StatusIndicator;
import org.junit.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

public class HostServiceItemMapperTest {

    private static final TAgentHost AGENT = TAgentHost.builder().name("agent-1").build();

    private static HostServiceItem map(ServiceStatus status) {
        return HostServiceItemMapper.toHostService(AGENT,
                ServiceInfoItem.builder().name("billing").status(status).build());
    }

    @Test
    public void builds_fqsn_from_host_and_service() {
        assertThat(map(ServiceStatus.builder().state(ServiceStateType.UP).build()).getFqsn())
                .isEqualTo("agent-1/billing");
    }

    @Test
    public void up_state_maps_to_running_success() {
        HostServiceItem item = map(ServiceStatus.builder().state(ServiceStateType.UP).build());

        assertThat(item.getStatusName()).isEqualTo("Running");
        assertThat(item.getStatusIndicator()).isEqualTo(StatusIndicator.SUCCESS);
    }

    @Test
    public void down_state_maps_to_down_stopped() {
        HostServiceItem item = map(ServiceStatus.builder().state(ServiceStateType.DOWN).build());

        assertThat(item.getStatusName()).isEqualTo("Down");
        assertThat(item.getStatusIndicator()).isEqualTo(StatusIndicator.STOPPED);
    }

    @Test
    public void up_want_down_maps_to_warning() {
        assertThat(map(ServiceStatus.builder().state(ServiceStateType.UP_WANT_DOWN).build()).getStatusIndicator())
                .isEqualTo(StatusIndicator.WARNING);
    }

    @Test
    public void up_paused_maps_to_loading() {
        assertThat(map(ServiceStatus.builder().state(ServiceStateType.UP_PAUSED).build()).getStatusIndicator())
                .isEqualTo(StatusIndicator.LOADING);
    }

    @Test
    public void error_state_maps_to_error_indicator() {
        assertThat(map(ServiceStatus.builder().state(ServiceStateType.ERROR).build()).getStatusIndicator())
                .isEqualTo(StatusIndicator.ERROR);
    }

    @Test
    public void missing_state_maps_to_error() {
        HostServiceItem item = map(ServiceStatus.builder().build());

        assertThat(item.getStatusName()).isEqualTo("No state");
        assertThat(item.getStatusIndicator()).isEqualTo(StatusIndicator.ERROR);
    }

    @Test
    public void format_age_is_empty_for_null_date() {
        assertThat(HostServiceItemMapper.formatAge(System.currentTimeMillis(), null)).isEmpty();
    }

    @Test
    public void format_age_of_recent_time_is_a_short_duration() {
        long now = 1_000_000_000_000L;
        Date oneMinuteAgo = new Date(now - 60_000);

        assertThat(HostServiceItemMapper.formatAge(now, oneMinuteAgo)).isEqualTo("1m");
    }

    @Test
    public void format_age_over_a_day_is_a_period() {
        long now = 1_000_000_000_000L;
        Date threeDaysAgo = new Date(now - 3L * 86_400_000);

        assertThat(HostServiceItemMapper.formatAge(now, threeDaysAgo)).contains("D");
    }

    @Test
    public void when_is_formatted_when_present() {
        HostServiceItem item = map(ServiceStatus.builder().state(ServiceStateType.UP).when(new Date()).build());

        assertThat(item.getWhenFormatted()).isNotBlank();
    }
}
