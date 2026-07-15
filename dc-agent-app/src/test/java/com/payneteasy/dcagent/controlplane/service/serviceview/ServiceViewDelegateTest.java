package com.payneteasy.dcagent.controlplane.service.serviceview;

import com.payneteasy.dcagent.controlplane.service.supervise.ISuperviseService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceViewResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

public class ServiceViewDelegateTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final String SERVICE = "billing";

    private static ISuperviseService supervise() {
        return (ISuperviseService) Proxy.newProxyInstance(
                ServiceViewDelegateTest.class.getClassLoader(),
                new Class[]{ISuperviseService.class},
                (proxy, method, args) -> {
                    if ("getServiceInfo".equals(method.getName())) {
                        return ServiceInfoItem.builder().name((String) args[0]).build();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private File servicesDirWithFiles() throws Exception {
        File servicesDir = folder.newFolder("services");
        File serviceDir = new File(servicesDir, SERVICE);
        File logDir = new File(serviceDir, "log");
        assertThat(logDir.mkdirs()).isTrue();
        Files.write(new File(serviceDir, "run").toPath(), "run-content".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(logDir, "run").toPath(), "log-run-content".getBytes(StandardCharsets.UTF_8));
        return servicesDir;
    }

    private ServiceViewResponse view() throws Exception {
        return new ServiceViewDelegate(servicesDirWithFiles(), supervise()).getServiceView(SERVICE);
    }

    @Test
    public void reads_the_run_file_content() throws Exception {
        assertThat(view().getRunContent()).isEqualTo("run-content");
    }

    @Test
    public void reads_the_log_run_file_content() throws Exception {
        assertThat(view().getLogRunContent()).isEqualTo("log-run-content");
    }

    @Test
    public void includes_service_info_and_name() throws Exception {
        ServiceViewResponse response = view();

        assertThat(response.getServiceName()).isEqualTo(SERVICE);
        assertThat(response.getServiceInfo()).isNotNull();
    }

    @Test
    public void reports_missing_log_file_gracefully() throws Exception {
        assertThat(view().getLastLogLines()).contains("No log file");
    }
}
