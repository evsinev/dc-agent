package com.payneteasy.dcagent;

import org.eclipse.jetty.server.Server;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ControlPlaneTokenCheckTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void enabled_with_the_default_token_is_refused() {
        assertThatThrownBy(() -> DcAgentApplication.checkControlPlaneToken(true, IStartupConfig.DEFAULT_CONTROL_PLANE_TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONTROL_PLANE_TOKEN");
    }

    @Test
    public void enabled_with_an_empty_token_is_refused() {
        assertThatThrownBy(() -> DcAgentApplication.checkControlPlaneToken(true, "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DcAgentApplication.checkControlPlaneToken(true, "  ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DcAgentApplication.checkControlPlaneToken(true, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void enabled_with_an_own_token_is_fine() {
        assertThatCode(() -> DcAgentApplication.checkControlPlaneToken(true, "my-own-secret")).doesNotThrowAnyException();
    }

    @Test
    public void disabled_with_the_default_token_is_fine() {
        assertThatCode(() -> DcAgentApplication.checkControlPlaneToken(false, IStartupConfig.DEFAULT_CONTROL_PLANE_TOKEN))
                .doesNotThrowAnyException();
    }

    /** Through the real start: refused before Jetty binds the port (fails if start skips the check). */
    @Test
    public void start_refuses_the_default_token_before_binding_the_port() throws Exception {
        int                port = freePort();
        DcAgentApplication app  = new DcAgentApplication();
        try {
            assertThatThrownBy(() -> app.start(config(port, IStartupConfig.DEFAULT_CONTROL_PLANE_TOKEN)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CONTROL_PLANE_TOKEN");

            try (ServerSocket ignored = new ServerSocket(port)) {
                // the port is still free: Jetty was never started
            }
        } finally {
            stopJetty(app);
        }
    }

    private IStartupConfig config(int aPort, String aToken) {
        File dir = tmp.getRoot();
        return (IStartupConfig) Proxy.newProxyInstance(
                ControlPlaneTokenCheckTest.class.getClassLoader(),
                new Class[]{IStartupConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getJettyPort"          -> aPort;
                    case "getJettyContext"       -> "/dc-agent";
                    case "getConfigDir", "getOptDir", "getTempDir", "getServicesDefinitionDir",
                         "getServicesDir", "getServicesLogDir" -> dir;
                    case "getContainerRuntime"   -> "docker";
                    case "controlPlaneToken"     -> aToken;
                    case "isControlPlaneEnabled" -> true;
                    case "getSvcCommand"         -> "/nonexistent/svc";
                    case "getSvstatCommand"      -> "/nonexistent/svstat";
                    case "appInstanceName"       -> "dc-agent-test";
                    case "appStatusToken"        -> "status-token";
                    default                      -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> aType) {
        if (aType == boolean.class) {
            return false;
        }
        if (aType == int.class) {
            return 0;
        }
        if (aType == long.class) {
            return 0L;
        }
        return null;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void stopJetty(DcAgentApplication aApp) throws Exception {
        Field field = DcAgentApplication.class.getDeclaredField("jetty");
        field.setAccessible(true);
        Server jetty = (Server) field.get(aApp);
        if (jetty != null) {
            jetty.stop();
        }
    }
}
