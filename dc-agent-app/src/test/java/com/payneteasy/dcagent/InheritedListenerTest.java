package com.payneteasy.dcagent;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WEB_SERVER_INHERITED: only a bound, listening TCP socket on fd 0 is accepted; never a fallback. */
public class InheritedListenerTest {

    private static final Duration IDLE = Duration.ofSeconds(30);

    @Test
    public void nothing_inherited_refuses_the_start() {
        assertThatThrownBy(() -> DcAgentApplication.createInheritedConnector(new Server(), null, IDLE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fd 0 is not a listening TCP socket")
                .hasMessageContaining("nothing inherited");
    }

    @Test
    public void a_connected_socket_is_not_a_listener() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel client = SocketChannel.open(listener.getLocalAddress())) {
                assertThatThrownBy(() -> DcAgentApplication.createInheritedConnector(new Server(), client, IDLE))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("fd 0 is not a listening TCP socket");
            }
        }
    }

    @Test
    public void an_unbound_server_socket_is_refused() throws Exception {
        try (ServerSocketChannel unbound = ServerSocketChannel.open()) {
            assertThatThrownBy(() -> DcAgentApplication.createInheritedConnector(new Server(), unbound, IDLE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("fd 0 is not a listening TCP socket");
        }
    }

    @Test
    public void a_listening_socket_makes_a_connector_that_inherits_it() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            ServerConnector connector = DcAgentApplication.createInheritedConnector(new Server(), listener, IDLE);
            assertThat(connector.isInheritChannel()).isTrue();
            assertThat(connector.getIdleTimeout()).isEqualTo(IDLE.toMillis());
        }
    }
}
