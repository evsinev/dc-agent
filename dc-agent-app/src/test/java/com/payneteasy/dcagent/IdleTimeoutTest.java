package com.payneteasy.dcagent;

import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.time.Duration;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A zip-archive-version call is silent while it waits; Jetty's 30 s default would cut it. Jetty 12
 * ee8 does not apply the idle timeout while a synchronous servlet is running, so a long reload would
 * pass with any value — checked instead: the value on the real connector of the agent, and the
 * connector's behaviour on a pause in the middle of a request body.
 */
public class IdleTimeoutTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void the_agent_connector_has_the_configured_idle_timeout() throws Exception {
        DcAgentApplication app = new DcAgentApplication();
        try {
            app.start(config("7m"));
            Connector[] connectors = app.jetty().getConnectors();

            assertThat(connectors).hasSize(1);
            assertThat(((ServerConnector) connectors[0]).getIdleTimeout()).isEqualTo(Duration.ofMinutes(7).toMillis());
        } finally {
            if (app.jetty() != null) {
                app.jetty().stop();
            }
        }
    }

    @Test
    public void a_pause_in_the_body_longer_than_the_idle_timeout_cuts_the_request() throws Exception {
        assertThat(postWithPause(Duration.ofSeconds(1), 2000)).doesNotStartWith("HTTP/1.1 200");
    }

    @Test
    public void a_pause_shorter_than_the_idle_timeout_is_fine() throws Exception {
        assertThat(postWithPause(Duration.ofSeconds(5), 2000)).startsWith("HTTP/1.1 200");
    }

    /** A reading servlet behind {@link DcAgentApplication#createConnector}; half the body, a pause, the rest. */
    private static String postWithPause(Duration aIdle, long aPauseMillis) throws Exception {
        Server                server  = new Server();
        ServerConnector       connector = DcAgentApplication.createConnector(server, 0, aIdle);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest aRequest, HttpServletResponse aResponse) throws IOException {
                aRequest.getInputStream().readAllBytes();
                aResponse.setStatus(200);
                aResponse.getWriter().write("read");
            }
        }), "/*");
        server.setHandler(context);
        server.start();
        try (Socket socket = new Socket("127.0.0.1", connector.getLocalPort())) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /x HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\nConnection: close\r\n\r\n12345").getBytes(US_ASCII));
            out.flush();
            Thread.sleep(aPauseMillis);
            try {
                out.write("67890".getBytes(US_ASCII));
                out.flush();
            } catch (IOException e) {
                return "cut: " + e.getClass().getSimpleName();
            }
            InputStream in = socket.getInputStream();
            try {
                return new String(in.readAllBytes(), US_ASCII);
            } catch (IOException e) {
                return "cut: " + e.getClass().getSimpleName();
            }
        } finally {
            server.stop();
        }
    }

    private IStartupConfig config(String aIdle) {
        File dir = tmp.getRoot();
        return (IStartupConfig) Proxy.newProxyInstance(
                IdleTimeoutTest.class.getClassLoader(),
                new Class[]{IStartupConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getJettyPort"          -> 0;
                    case "getJettyContext"       -> "/dc-agent";
                    case "getJettyIdleTimeout"   -> aIdle;
                    case "getConfigDir", "getOptDir", "getTempDir", "getServicesDefinitionDir",
                         "getServicesDir", "getServicesLogDir" -> dir;
                    case "getContainerRuntime"   -> "docker";
                    case "isControlPlaneEnabled" -> false;
                    case "getSvcCommand"         -> "/nonexistent/svc";
                    case "getSvstatCommand"      -> "/nonexistent/svstat";
                    case "appInstanceName"       -> "dc-agent-test";
                    case "appStatusToken"        -> "status-token";
                    default                      -> method.getReturnType() == boolean.class ? false
                            : method.getReturnType() == int.class ? (Object) 0 : null;
                });
    }
}
