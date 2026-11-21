package com.payneteasy.dcagent.jetty;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class AuditFilterTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger                      logger   = (Logger) LoggerFactory.getLogger(AuditFilter.class);

    @Before
    public void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @After
    public void detach() {
        logger.detachAppender(appender);
    }

    private static HttpServletRequest request(byte[] aBody, Map<String, Object> aAttributes) {
        return request(aBody, aAttributes, aBody.length);
    }

    private static HttpServletRequest request(byte[] aBody, Map<String, Object> aAttributes, long aContentLength) {
        ByteArrayInputStream body = new ByteArrayInputStream(aBody);
        ServletInputStream stream = new ServletInputStream() {
            @Override
            public int read() {
                return body.read();
            }

            @Override
            public boolean isFinished() {
                return body.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener aListener) {
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(AuditFilterTest.class.getClassLoader(), new Class[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInputStream" -> stream;
                    case "setAttribute"   -> aAttributes.put((String) args[0], args[1]);
                    case "getAttribute"   -> aAttributes.get((String) args[0]);
                    case "getMethod"      -> "POST";
                    case "getRequestURI"  -> "/dc-agent/zip-archive/site";
                    case "getContentLengthLong" -> aContentLength;
                    default               -> null;
                });
    }

    private static HttpServletResponse response(int aStatus) {
        return (HttpServletResponse) Proxy.newProxyInstance(AuditFilterTest.class.getClassLoader(), new Class[]{HttpServletResponse.class},
                (proxy, method, args) -> "getStatus".equals(method.getName()) ? aStatus : null);
    }

    @Test
    public void an_authorized_command_leaves_one_line_with_its_body_hash_and_the_request_id() throws Exception {
        byte[] body = "zip-bytes".getBytes(UTF_8);
        Map<String, Object> attributes = new HashMap<>();
        String[] seenId = new String[1];

        new AuditFilter().doFilter(request(body, attributes), response(200), (req, res) -> {
            HttpServletRequest http = (HttpServletRequest) req;
            seenId[0] = (String) http.getAttribute(AuditFilter.REQUEST_ID);
            http.setAttribute(AuditFilter.AUTHORIZED_BY, "GitLab token (key gitlab, project p, ref main, job 2)");
            http.getInputStream().readAllBytes();
        });

        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        assertThat(appender.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("POST /dc-agent/zip-archive/site by GitLab token (key gitlab")
                .contains("status 200").contains("body 9 bytes (complete) sha256 " + sha).contains("request " + seenId[0]);
    }

    @Test
    public void an_unauthorized_request_leaves_no_audit_line() throws Exception {
        new AuditFilter().doFilter(request(new byte[0], new HashMap<>()), response(401), (req, res) -> { });

        assertThat(appender.list).isEmpty();
    }

    @Test
    public void a_body_refused_before_it_was_read_is_marked_not_read() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        new AuditFilter().doFilter(request("zip".getBytes(UTF_8), attributes), response(413), (req, res) ->
                ((HttpServletRequest) req).setAttribute(AuditFilter.AUTHORIZED_BY, "static key ****12345678"));

        assertThat(appender.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("status 413").contains("body 0 bytes (not read) sha256 -");
    }

    private String audit(byte[] aBody, long aContentLength, int aRead) throws Exception {
        appender.list.clear();
        new AuditFilter().doFilter(request(aBody, new HashMap<>(), aContentLength), response(500), (req, res) -> {
            HttpServletRequest http = (HttpServletRequest) req;
            http.setAttribute(AuditFilter.AUTHORIZED_BY, "static key ****12345678");
            if (aRead < 0) {
                http.getInputStream().readAllBytes();
            } else {
                http.getInputStream().readNBytes(aRead);
            }
        });
        return appender.list.get(0).getFormattedMessage();
    }

    @Test
    public void a_prefix_of_a_longer_body_is_partial_the_whole_body_complete() throws Exception {
        byte[] body = new byte[100];
        assertThat(audit(body, 100, 10)).contains("body 10 bytes (partial)");
        assertThat(audit(body, 100, -1)).contains("body 100 bytes (complete)");
    }

    @Test
    public void without_content_length_only_the_end_of_the_stream_makes_it_complete() throws Exception {
        byte[] body = new byte[100];
        assertThat(audit(body, -1, 100)).contains("body 100 bytes (partial)");   // all bytes, but no EOF seen
        assertThat(audit(body, -1, -1)).contains("body 100 bytes (complete)");   // read to EOF
    }

    @Test
    public void a_body_read_through_the_reader_is_counted() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        new AuditFilter().doFilter(request("hello world".getBytes(UTF_8), attributes), response(200), (req, res) -> {
            HttpServletRequest http = (HttpServletRequest) req;
            http.setAttribute(AuditFilter.AUTHORIZED_BY, "static key ****12345678");
            http.getReader().lines().count();
        });

        assertThat(appender.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("body 11 bytes (complete)");
    }
}
