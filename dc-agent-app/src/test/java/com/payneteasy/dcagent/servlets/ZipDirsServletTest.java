package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.config.service.impl.ConfigServiceImpl;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** zip-dirs with a good key: the target stays inside {@code dir}; the normal layouts keep working. */
public class ZipDirsServletTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path           root;
    private Path           dir;
    private ZipDirsServlet servlet;

    @Before
    public void setUp() throws IOException {
        root = tmp.getRoot().toPath().toRealPath();
        dir  = Files.createDirectories(root.resolve("versions"));
        Path config = Files.createDirectories(root.resolve("config"));
        Files.writeString(config.resolve("app.json"), "{\"type\":\"ZIP_DIRS\",\"dir\":"
                + Gsons.PRETTY_GSON.toJson(dir.toString()) + ",\"apiKeys\":{\"good-key\":\"ci\"}}");
        servlet = new ZipDirsServlet(new ConfigServiceImpl(config.toFile(), Gsons.PRETTY_GSON));
    }

    @Test
    public void extracts_into_the_sub_path() throws IOException {
        servlet.doPost(request("/dc-agent/zip-dirs/app/a/b"), response());

        assertThat(dir.resolve("a/b/hello.txt")).hasContent("hello");
    }

    @Test
    public void extracts_straight_into_dir_without_a_sub_path() throws IOException {
        servlet.doPost(request("/dc-agent/zip-dirs/app"), response());

        assertThat(dir.resolve("hello.txt")).hasContent("hello");
    }

    @Test
    public void refuses_a_dot_dot_segment_and_creates_nothing() {
        assertThatThrownBy(() -> servlet.doPost(request("/dc-agent/zip-dirs/app/.."), response()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servlet.doPost(request("/dc-agent/zip-dirs/app/a/../../x"), response()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(root.resolve("hello.txt")).doesNotExist();
        assertThat(root.resolve("x")).doesNotExist();
        assertThat(dir.resolve("a")).doesNotExist();
        assertThat(dir).isEmptyDirectory();
    }

    @Test
    public void refuses_a_dot_segment_and_creates_nothing() {
        assertThatThrownBy(() -> servlet.doPost(request("/dc-agent/zip-dirs/app/./a"), response()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(dir).isEmptyDirectory();
    }

    @Test
    public void refuses_a_link_inside_dir_that_points_outside() throws IOException {
        Path outside = Files.createDirectories(root.resolve("outside"));
        Files.createSymbolicLink(dir.resolve("link"), outside);

        assertThatThrownBy(() -> servlet.doPost(request("/dc-agent/zip-dirs/app/link/x"), response()))
                .isInstanceOf(SecurityException.class);

        assertThat(outside).isEmptyDirectory();
    }

    private static HttpServletRequest request(String aUri) throws IOException {
        ByteArrayInputStream body = new ByteArrayInputStream(zipBytes());
        ServletInputStream   in   = new ServletInputStream() {
            @Override public int read()                               { return body.read(); }
            @Override public boolean isFinished()                     { return body.available() == 0; }
            @Override public boolean isReady()                        { return true; }
            @Override public void setReadListener(ReadListener aList) { throw new UnsupportedOperationException(); }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                ZipDirsServletTest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRequestURI"  -> aUri;
                    case "getHeader"      -> "api-key".equals(args[0]) ? "good-key" : null;
                    case "getInputStream" -> in;
                    default               -> null;
                });
    }

    private static HttpServletResponse response() {
        return (HttpServletResponse) Proxy.newProxyInstance(
                ZipDirsServletTest.class.getClassLoader(),
                new Class[]{HttpServletResponse.class},
                (proxy, method, args) -> null);
    }

    private static byte[] zipBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("hello.txt"));
            out.write("hello".getBytes(UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }
}
