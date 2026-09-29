package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.exception.WrongApiKeyException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The agent is root: nothing may touch the disk before the api-key is checked. */
public class SaveArtifactServletTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path                dir;
    private SaveArtifactServlet servlet;

    @Before
    public void setUp() throws IOException {
        dir = tmp.getRoot().toPath().toRealPath().resolve("artifacts");
        TSaveArtifactConfig config = TSaveArtifactConfig.builder()
                .dir(dir.toString())
                .extension("apk")
                .replaceDirChars("__")
                .apiKeys(Map.of("good-key", "ci"))
                .build();
        servlet = new SaveArtifactServlet(configService(config));
    }

    @Test
    public void creates_nothing_without_a_key() {
        assertThatThrownBy(() -> servlet.doPost(request("/dc-agent/save-artifact/app/release__1.0", Map.of()), response()))
                .isInstanceOf(WrongApiKeyException.class);

        assertThat(dir).doesNotExist();
    }

    @Test
    public void creates_nothing_with_a_wrong_key() {
        assertThatThrownBy(() -> servlet.doPost(request("/dc-agent/save-artifact/app/release__1.0", Map.of("api-key", "bad-key")), response()))
                .isInstanceOf(WrongApiKeyException.class);

        assertThat(dir).doesNotExist();
    }

    @Test
    public void stores_the_artifact_with_a_good_key() throws IOException {
        servlet.doPost(request("/dc-agent/save-artifact/app/release__1.0", Map.of("api-key", "good-key")), response());

        assertThat(dir.resolve("release/1.0.apk")).hasContent("artifact");
    }

    @Test
    public void refuses_an_extension_header_that_is_a_path_even_with_a_good_key() {
        Map<String, String> headers = new HashMap<>();
        headers.put("api-key", "good-key");
        headers.put("x-dc-agent-file-extension", "x/../../../escaped");

        assertThatThrownBy(() -> servlet.doPost(request("/dc-agent/save-artifact/app/1.0", headers), response()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(dir).doesNotExist();
        assertThat(tmp.getRoot().toPath().resolve("escaped")).doesNotExist();
    }

    private static IConfigService configService(TSaveArtifactConfig aConfig) {
        return (IConfigService) Proxy.newProxyInstance(
                SaveArtifactServletTest.class.getClassLoader(),
                new Class[]{IConfigService.class},
                (proxy, method, args) -> {
                    if ("getSaveArtifactConfig".equals(method.getName()) && "app".equals(args[0])) {
                        return aConfig;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static HttpServletRequest request(String aUri, Map<String, String> aHeaders) {
        ByteArrayInputStream body = new ByteArrayInputStream("artifact".getBytes(UTF_8));
        ServletInputStream   in   = new ServletInputStream() {
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
                throw new UnsupportedOperationException("setReadListener");
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                SaveArtifactServletTest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRequestURI"  -> aUri;
                    case "getHeader"      -> aHeaders.get((String) args[0]);
                    case "getInputStream" -> in;
                    default               -> null;
                });
    }

    private static HttpServletResponse response() {
        return (HttpServletResponse) Proxy.newProxyInstance(
                SaveArtifactServletTest.class.getClassLoader(),
                new Class[]{HttpServletResponse.class},
                (proxy, method, args) -> null);
    }
}
