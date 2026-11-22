package com.payneteasy.dcagent.controlplane.service.supervise.impl;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.core.modules.jar.DaemontoolsServiceImpl;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStateType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.SuperviseState;
import com.payneteasy.dcagent.util.SimpleLogImpl;
import org.junit.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;


public class SuperviseServiceImplTest {

    private final SuperviseServiceImpl service = new SuperviseServiceImpl(
            new File("src/test/resources/services")
            , new DaemontoolsServiceImpl(new SimpleLogImpl(SuperviseServiceImplTest.class))
    );

    @Test
    public void lists_the_service_directories() {
        assertThat(service.listServices(VoidRequest.VOID_REQUEST))
                .extracting(ServiceInfoItem::getName)
                .contains("test-1");
    }

    @Test
    public void get_service_info_parses_the_status_file() {
        ServiceInfoItem item = service.getServiceInfo("test-1");

        assertThat(item.getName()).isEqualTo("test-1");
        // supervise/status: TAI64N 2^62 + 10 + 1711802120 s, 350857500 ns; pid 6741; want up; ok present
        assertThat(item.getStatus().getError()).isNull();
        assertThat(item.getStatus().getPid()).isEqualTo(6741);
        assertThat(item.getStatus().getState()).isEqualTo(ServiceStateType.UP);
        assertThat(item.getStatus().getSuperviseState()).isEqualTo(SuperviseState.SUPERVISE_RUNNING);
        assertThat(item.getStatus().getWhen().toInstant()).isEqualTo(java.time.Instant.parse("2024-03-30T12:35:20.350Z"));
    }

    @Test
    public void get_service_info_of_missing_service_yields_error_status() {
        ServiceInfoItem item = service.getServiceInfo("does-not-exist");

        assertThat(item.getName()).isEqualTo("does-not-exist");
        assertThat(item.getStatus().getError()).isNotNull();
    }
}
