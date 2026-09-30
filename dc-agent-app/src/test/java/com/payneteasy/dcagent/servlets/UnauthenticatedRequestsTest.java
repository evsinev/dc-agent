package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.config.service.impl.ConfigServiceImpl;
import com.payneteasy.dcagent.core.exception.WrongApiKeyException;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemCheckImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import com.payneteasy.dcagent.core.modules.docker.runtime.ContainerRuntime;
import com.payneteasy.dcagent.core.modules.jar.DaemontoolsServiceImpl;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import com.payneteasy.dcagent.util.SimpleLogImpl;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent is root: a request that is not authorized for the command must be refused before
 * anything else happens — no body read, nothing on disk — and every refusal must look the same
 * from outside (no config path, no "exists / does not exist" difference). Servlets are wired as in
 * {@code DcAgentApplication}, over a real {@link ConfigServiceImpl} and temp dirs.
 */
public class UnauthenticatedRequestsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path   root;
    private Path   data;
    private byte[] zip;

    private Map<String, HttpServlet> configured;
    private Map<String, HttpServlet> unconfigured;
    private Map<String, HttpServlet> wrongType;
    private Map<String, Map<String, HttpServlet>> dummies;

    @Before
    public void setUp() throws IOException {
        root = tmp.getRoot().toPath().toRealPath();
        data = Files.createDirectories(root.resolve("data"));
        Path config = Files.createDirectories(root.resolve("config"));
        Path empty  = Files.createDirectories(root.resolve("config-empty"));
        Files.writeString(data.resolve("keep.txt"), "keep");

        String dir = json(data.toString());
        writeConfig(config, "za.json", "{\"type\":\"ZIP_ARCHIVE\",\"dir\":" + dir + ",\"apiKeys\":{\"key-za\":\"ci\"}}");
        writeConfig(config, "zd.json", "{\"type\":\"ZIP_DIRS\",\"dir\":" + dir + ",\"apiKeys\":{\"key-zd\":\"ci\"}}");
        writeConfig(config, "sa.json", "{\"type\":\"SAVE_ARTIFACT\",\"dir\":" + dir + ",\"extension\":\"zip\",\"apiKeys\":{\"key-sa\":\"ci\"}}");
        writeConfig(config, "fetch-url.json", "{\"type\":\"FETCH_URL\",\"apiKeys\":{\"key-fu\":\"ci\"}}");
        writeConfig(config, "dc-docker.json", "{\"type\":\"DOCKER\",\"apiKeys\":{\"key-dc\":\"ci\"}}");
        writeConfig(config, "jr.json", jarConfig("JAR", "key-jr", "jarFilename", "app.jar"));
        writeConfig(config, "wr.json", jarConfig("WAR", "key-wr", "warFilename", "app.war"));
        writeConfig(config, "nd.json", jarConfig("NODE", "key-nd", "jarFilename", "app.zip"));
        writeConfig(config, "empty.json", "");
        writeConfig(config, "nulls.json", "null");
        writeConfig(config, "broken.yml", "dir: [unclosed\n  apiKeys: {");

        // Fixed-name configs (fetch-url, dc-docker) of the wrong type, with the endpoint's own key
        Path wrong = Files.createDirectories(root.resolve("config-wrong-type"));
        writeConfig(wrong, "fetch-url.json", "{\"type\":\"ZIP_ARCHIVE\",\"dir\":" + dir + ",\"apiKeys\":{\"key-fu\":\"ci\"}}");
        writeConfig(wrong, "dc-docker.json", "{\"type\":\"DOCKER_PUSH\",\"apiKeys\":{\"key-dc\":\"ci\"}}");

        configured   = servlets(config);
        unconfigured = servlets(empty);
        wrongType    = servlets(wrong);
        dummies      = new java.util.LinkedHashMap<>();
        for (String[] dummy : new String[][]{{"empty", ".json", ""}, {"nulls", ".json", "null"}, {"broken", ".yml", "dir: [unclosed\n  apiKeys: {"}}) {
            Path dummyDir = Files.createDirectories(root.resolve("config-" + dummy[0]));
            writeConfig(dummyDir, "fetch-url" + dummy[1], dummy[2]);
            writeConfig(dummyDir, "dc-docker" + dummy[1], dummy[2]);
            dummies.put(dummy[0], servlets(dummyDir));
        }
        zip          = zipBytes();
    }

    // ── Cases ────────────────────────────────────────────────────────────

    private List<Case> cases() {
        List<Case> cases = new ArrayList<>();

        // Endpoints with a command name from the URL: {endpoint, uri for a name, own key, foreign key}
        named(cases, "zip-archive"  , name -> "/dc-agent/zip-archive/" + name        , "za", "sa", "key-sa");
        named(cases, "zip-dirs"     , name -> "/dc-agent/zip-dirs/" + name + "/a/b"  , "zd", "sa", "key-sa");
        named(cases, "save-artifact", name -> "/dc-agent/save-artifact/" + name + "/1.0", "sa", "za", "key-za");
        named(cases, "jar"          , name -> "/dc-agent/jar/" + name                , "jr", "wr", "key-wr");
        named(cases, "war"          , name -> "/dc-agent/war/" + name                , "wr", "nd", "key-nd");
        named(cases, "node"         , name -> "/dc-agent/node/" + name               , "nd", "jr", "key-jr");

        // zip-dirs without a command name at all
        cases.add(new Case("zip-dirs no name, wrong key", configured, "zip-dirs", "/dc-agent/zip-dirs", "wrong"));

        // Fixed-name endpoints: the config name is not in the URL
        fixed(cases, "fetch-url"   , FETCH_URI       , "key-fu", "key-za");
        fixed(cases, "docker-push" , DOCKER_PUSH_URI , "key-dc", "key-za");
        fixed(cases, "docker-check", DOCKER_CHECK_URI, "key-dc", "key-za");
        return cases;
    }

    private void named(List<Case> aCases, String aEndpoint, java.util.function.Function<String, String> aUri,
                       String aOwn, String aForeignName, String aForeignKey) {
        aCases.add(new Case(aEndpoint + " no key"            , configured  , aEndpoint, aUri.apply(aOwn), null));
        aCases.add(new Case(aEndpoint + " wrong key"         , configured  , aEndpoint, aUri.apply(aOwn), "wrong"));
        aCases.add(new Case(aEndpoint + " unknown name"      , configured  , aEndpoint, aUri.apply("ghost"), "wrong"));
        aCases.add(new Case(aEndpoint + " unknown name + key of another command", configured, aEndpoint,
                aUri.apply("ghost"), aForeignKey));
        aCases.add(new Case(aEndpoint + " key of " + aForeignName + " (other type, same dir)", configured, aEndpoint,
                aUri.apply(aForeignName), aForeignKey));
        for (String dummy : new String[]{"empty", "nulls", "broken"}) {
            aCases.add(new Case(aEndpoint + " config " + dummy, configured, aEndpoint, aUri.apply(dummy), "wrong"));
        }
    }

    private void fixed(List<Case> aCases, String aEndpoint, String aUri, String aOwnKey, String aForeignKey) {
        aCases.add(new Case(aEndpoint + " no key"                 , configured  , aEndpoint, aUri, null));
        aCases.add(new Case(aEndpoint + " wrong key"              , configured  , aEndpoint, aUri, "wrong"));
        aCases.add(new Case(aEndpoint + " key of another command" , configured  , aEndpoint, aUri, aForeignKey));
        aCases.add(new Case(aEndpoint + " not configured"         , unconfigured, aEndpoint, aUri, "wrong"));
        aCases.add(new Case(aEndpoint + " config of another type, its own key", wrongType, aEndpoint, aUri, aOwnKey));
        for (Map.Entry<String, Map<String, HttpServlet>> dummy : dummies.entrySet()) {
            aCases.add(new Case(aEndpoint + " config " + dummy.getKey(), dummy.getValue(), aEndpoint, aUri, "wrong"));
        }
    }

    private static final String FETCH_URI        = "/dc-agent/fetch-url/http://127.0.0.1:1/x"; // SsrfGuard blocks it
    private static final String DOCKER_PUSH_URI  = "/dc-agent/docker/push/svc";
    private static final String DOCKER_CHECK_URI = "/dc-agent/docker/check/svc";

    // ── The test ─────────────────────────────────────────────────────────

    @Test
    public void unauthorized_requests_change_nothing_read_nothing_and_look_the_same() throws IOException {
        List<String> problems = new ArrayList<>();
        Set<String>  messages = new LinkedHashSet<>();

        for (Case c : cases()) {
            Map<String, Long> before    = snapshot();
            AtomicInteger     bodyReads = new AtomicInteger();
            Throwable         thrown    = null;
            try {
                invoke(c.servlets.get(c.endpoint), c.endpoint, request(c.uri, c.key, bodyReads), response());
            } catch (Throwable e) {
                thrown = e;
            }

            if (!(thrown instanceof WrongApiKeyException)) {
                problems.add(c.title + ": expected WrongApiKeyException, got " + describe(thrown));
            } else {
                messages.add(thrown.getMessage());
            }
            if (thrown != null && String.valueOf(thrown.getMessage()).contains(root.toString())) {
                problems.add(c.title + ": message reveals the config/data path: " + thrown.getMessage());
            }
            if (bodyReads.get() != 0) {
                problems.add(c.title + ": request body was read " + bodyReads.get() + " time(s)");
            }
            Map<String, Long> after = snapshot();
            if (!before.equals(after)) {
                problems.add(c.title + ": disk changed: " + diff(before, after));
            }
        }

        if (messages.size() > 1) {
            problems.add("refusals are distinguishable, messages: " + messages);
        }
        assertThat(problems).as("unauthorized requests").isEmpty();
    }

    /**
     * The other side: with its own key every endpoint gets past authorization (so a wrong expected
     * type in a servlet shows up here). What happens next — a failed deploy of a stub zip, a blocked
     * url — does not matter, only that it is not the authorization refusal.
     */
    @Test
    public void own_key_passes_authorization_on_every_endpoint() {
        Map<String, String> own = new java.util.LinkedHashMap<>();
        own.put("zip-archive"  , "/dc-agent/zip-archive/za");
        own.put("zip-dirs"     , "/dc-agent/zip-dirs/zd/a/b");
        own.put("save-artifact", "/dc-agent/save-artifact/sa/1.0");
        own.put("jar"          , "/dc-agent/jar/jr");
        own.put("war"          , "/dc-agent/war/wr");
        own.put("node"         , "/dc-agent/node/nd");
        own.put("fetch-url"    , FETCH_URI);
        own.put("docker-push"  , DOCKER_PUSH_URI);
        own.put("docker-check" , DOCKER_CHECK_URI);
        Map<String, String> keys = Map.of("zip-archive", "key-za", "zip-dirs", "key-zd", "save-artifact", "key-sa",
                "jar", "key-jr", "war", "key-wr", "node", "key-nd",
                "fetch-url", "key-fu", "docker-push", "key-dc", "docker-check", "key-dc");

        List<String> refused = new ArrayList<>();
        for (Map.Entry<String, String> entry : own.entrySet()) {
            try {
                invoke(configured.get(entry.getKey()), entry.getKey(),
                        request(entry.getValue(), keys.get(entry.getKey()), new AtomicInteger()), response());
            } catch (WrongApiKeyException e) {
                refused.add(entry.getKey());
            } catch (Throwable e) {
                // past authorization: the action itself failed on the stub input, fine here
            }
        }
        assertThat(refused).as("endpoints refusing their own key").isEmpty();
    }

    // ── Wiring (as in DcAgentApplication) ────────────────────────────────

    private Map<String, HttpServlet> servlets(Path aConfigDir) {
        IConfigService         configService = new ConfigServiceImpl(aConfigDir.toFile(), Gsons.PRETTY_GSON);
        DaemontoolsServiceImpl daemontools   = new DaemontoolsServiceImpl(
                root.resolve("no-such-svc").toString(),
                root.resolve("no-such-svstat").toString(),
                new SimpleLogImpl(UnauthenticatedRequestsTest.class));
        TempDir               tempDir     = new TempDir(root.resolve("temp").toFile(), true);
        ServicesDefinitionDir definitions = new ServicesDefinitionDir(root.resolve("services").toFile());
        ServicesLogDir        logs        = new ServicesLogDir(root.resolve("services-log").toFile());
        ContainerRuntime      runtime     = ContainerRuntime.parse("docker");

        return Map.of(
                "zip-archive"  , new ZipArchiveServlet(configService),
                "zip-dirs"     , new ZipDirsServlet(configService),
                "fetch-url"    , new FetchUrlServlet(configService),
                "save-artifact", new SaveArtifactServlet(configService),
                "jar"          , new FetchUrlServlet.JarServlet(configService, daemontools),
                "war"          , new WarServlet(configService, daemontools),
                "node"         , new NodeServlet(configService, daemontools),
                "docker-push"  , new PushDockerServlet(configService, tempDir, definitions, logs, FileSystemWriterImpl::new, runtime),
                "docker-check" , new PushDockerServlet(configService, tempDir, definitions, logs, FileSystemCheckImpl::new, runtime));
    }

    private static void invoke(HttpServlet aServlet, String aEndpoint, HttpServletRequest aRequest,
                               HttpServletResponse aResponse) throws Exception {
        if ("fetch-url".equals(aEndpoint)) {
            ((FetchUrlServlet) aServlet).doGet(aRequest, aResponse);
        } else if (aServlet instanceof AbstractJarServlet jar) {
            jar.doPost(aRequest, aResponse);
        } else if (aServlet instanceof ZipArchiveServlet s) {
            s.doPost(aRequest, aResponse);
        } else if (aServlet instanceof ZipDirsServlet s) {
            s.doPost(aRequest, aResponse);
        } else if (aServlet instanceof SaveArtifactServlet s) {
            s.doPost(aRequest, aResponse);
        } else if (aServlet instanceof PushDockerServlet s) {
            s.doPost(aRequest, aResponse);
        } else {
            throw new IllegalArgumentException(aEndpoint);
        }
    }

    // ── Request / response stubs ─────────────────────────────────────────

    private HttpServletRequest request(String aUri, String aKey, AtomicInteger aBodyReads) {
        String pathInfo = aUri.startsWith("/dc-agent/fetch-url/") ? aUri.substring("/dc-agent/fetch-url".length()) : null;
        ByteArrayInputStream body = new ByteArrayInputStream(zip);
        ServletInputStream   in   = new ServletInputStream() {
            @Override public int read()                               { return body.read(); }
            @Override public boolean isFinished()                     { return body.available() == 0; }
            @Override public boolean isReady()                        { return true; }
            @Override public void setReadListener(ReadListener aList) { throw new UnsupportedOperationException(); }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                UnauthenticatedRequestsTest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRequestURI"  -> aUri;
                    case "getPathInfo"    -> pathInfo;
                    case "getMethod"      -> pathInfo != null ? "GET" : "POST";
                    case "getHeader"      -> "api-key".equals(args[0]) ? aKey : null;
                    case "getInputStream" -> {
                        aBodyReads.incrementAndGet();
                        yield in;
                    }
                    case "getReader"      -> {
                        aBodyReads.incrementAndGet();
                        yield new BufferedReader(new InputStreamReader(in, UTF_8));
                    }
                    default               -> null;
                });
    }

    private static HttpServletResponse response() {
        PrintWriter         writer = new PrintWriter(new StringWriter());
        ServletOutputStream out    = new ServletOutputStream() {
            private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
            @Override public void write(int b)                          { sink.write(b); }
            @Override public boolean isReady()                          { return true; }
            @Override public void setWriteListener(WriteListener aList) { throw new UnsupportedOperationException(); }
        };
        return (HttpServletResponse) Proxy.newProxyInstance(
                UnauthenticatedRequestsTest.class.getClassLoader(),
                new Class[]{HttpServletResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWriter"       -> writer;
                    case "getOutputStream" -> out;
                    default                -> null;
                });
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Map<String, Long> snapshot() throws IOException {
        Map<String, Long> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                files.put(root.relativize(path).toString(), Files.isDirectory(path) ? -1L : Files.size(path));
            }
        }
        return files;
    }

    private static String diff(Map<String, Long> aBefore, Map<String, Long> aAfter) {
        Set<String> changed = new LinkedHashSet<>();
        for (String path : aAfter.keySet()) {
            if (!aAfter.get(path).equals(aBefore.get(path))) {
                changed.add("+" + path);
            }
        }
        for (String path : aBefore.keySet()) {
            if (!aAfter.containsKey(path)) {
                changed.add("-" + path);
            }
        }
        return changed.toString();
    }

    private static String describe(Throwable aThrown) {
        return aThrown == null ? "no exception" : aThrown.getClass().getSimpleName() + ": " + aThrown.getMessage();
    }

    private static void writeConfig(Path aDir, String aFile, String aContent) throws IOException {
        Files.writeString(aDir.resolve(aFile), aContent);
    }

    private String jarConfig(String aType, String aKey, String aFileField, String aFile) {
        return "{\"type\":\"" + aType + "\",\"" + aFileField + "\":" + json(data.resolve(aFile).toString())
                + ",\"serviceName\":\"svc\",\"serviceDir\":" + json(root.resolve("services/svc").toString())
                + ",\"serviceLogFile\":" + json(root.resolve("services-log/svc/current").toString())
                + ",\"apiKeys\":{\"" + aKey + "\":\"ci\"}}";
    }

    private static String json(String aValue) {
        return Gsons.PRETTY_GSON.toJson(aValue);
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

    private record Case(String title, Map<String, HttpServlet> servlets, String endpoint, String uri, String key) {
    }
}
