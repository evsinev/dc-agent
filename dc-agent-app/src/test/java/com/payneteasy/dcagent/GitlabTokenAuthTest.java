package com.payneteasy.dcagent;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.payneteasy.dcagent.core.config.model.IApiKeys;
import com.payneteasy.dcagent.core.config.model.TCommandKey;
import com.payneteasy.dcagent.core.config.model.TKeyType;
import com.payneteasy.dcagent.jetty.CheckApiKeyAccess;
import com.sun.net.httpserver.HttpServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/** Bearer tokens through CheckApiKey (issuer file on disk, a fixed clock) and fetch-jwks. */
public class GitlabTokenAuthTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final TestTokens TOKENS = new TestTokens(2048);
    private static final long       NOW    = 1_795_215_862L;
    private static final Clock      CLOCK  = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final String     STATIC = "static-key-0123456789abcdef0123456789";

    private static HttpServletRequest request(Map<String, List<String>> aHeaders) {
        return request(aHeaders, new java.util.HashMap<>());
    }

    private static HttpServletRequest request(Map<String, List<String>> aHeaders, Map<String, Object> aAttributes) {
        return (HttpServletRequest) Proxy.newProxyInstance(GitlabTokenAuthTest.class.getClassLoader(), new Class[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeader"    -> aHeaders.containsKey(args[0]) ? aHeaders.get(args[0]).get(0) : null;
                    case "getHeaders"   -> Collections.enumeration(aHeaders.getOrDefault(args[0], List.of()));
                    case "setAttribute" -> aAttributes.put((String) args[0], args[1]);
                    case "getAttribute" -> aAttributes.get((String) args[0]);
                    default             -> null;
                });
    }

    private static String basic(String aPassword) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString(("ci:" + aPassword).getBytes(UTF_8));
    }

    private static Map<String, List<String>> headers(String... aNameValues) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 0; i < aNameValues.length; i += 2) {
            headers.computeIfAbsent(aNameValues[i], name -> new java.util.ArrayList<>()).add(aNameValues[i + 1]);
        }
        return headers;
    }

    private static IApiKeys config(boolean aWithJwt) {
        List<TCommandKey> keys = new java.util.ArrayList<>();
        keys.add(TCommandKey.builder().name("ci-static").type(TKeyType.SHA256).hash(TestConfigs.hash(STATIC)).build());
        if (aWithJwt) {
            Map<String, JsonElement> rule = new LinkedHashMap<>();
            rule.put("project_id", JsonParser.parseString("913"));
            rule.put("ref_protected", JsonParser.parseString("true"));
            keys.add(TCommandKey.builder().name("gitlab").type(TKeyType.GITLAB_JWT).issuer("gitlab-test").audience("dc-agent:test")
                    .rules(List.of(rule)).build());
        }
        return new IApiKeys() {
            @Override
            public Map<String, String> getApiKeys() {
                return null;
            }

            @Override
            public List<TCommandKey> getKeys() {
                return keys;
            }
        };
    }

    private Path keysDir() throws Exception {
        Path dir = tmp.newFolder("keys").toPath();
        Files.writeString(dir.resolve("gitlab-test.json"), TOKENS.issuerFile("2026-11-21T00:00:00Z"), UTF_8);
        return dir;
    }

    private static String token(String aExtra) {
        return TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW - 60, aExtra));
    }

    @Test
    public void a_good_token_passes_and_a_static_key_still_works() throws Exception {
        CheckApiKeyAccess check = new CheckApiKeyAccess(keysDir(), CLOCK);

        assertThat(check.reason(request(headers("Authorization", "Bearer " + token(""))), config(true))).isNull();
        assertThat(check.reason(request(headers("api-key", STATIC)), config(true))).isNull();
    }

    @Test
    public void a_token_never_counts_as_a_static_key_nor_the_other_way_round() throws Exception {
        CheckApiKeyAccess check = new CheckApiKeyAccess(keysDir(), CLOCK);

        assertThat(check.reason(request(headers("Authorization", "Bearer " + token(""))), config(false))).contains("no GITLAB_JWT key");
        assertThat(check.reason(request(headers("api-key", token(""))), config(true))).isEqualTo("api key not found");
        assertThat(check.reason(request(headers("Authorization", "Bearer " + STATIC)), config(true))).contains("GitLab token refused");
    }

    @Test
    public void ambiguous_credentials_are_refused() throws Exception {
        CheckApiKeyAccess check = new CheckApiKeyAccess(keysDir(), CLOCK);

        assertThat(check.reason(request(headers("Authorization", "Bearer " + token(""), "api-key", STATIC)), config(true))).contains("both");
        assertThat(check.reason(request(headers("Authorization", "Bearer " + token(""), "Authorization", "Bearer " + token(""))), config(true)))
                .contains("repeated");
        assertThat(check.reason(request(headers("api-key", STATIC, "api-key", STATIC)), config(true))).contains("repeated");
    }

    @Test
    public void header_edge_cases() throws Exception {
        CheckApiKeyAccess check = new CheckApiKeyAccess(keysDir(), CLOCK);

        // the scheme name is case-insensitive (RFC 9110); an empty token is no token, and no static key either
        assertThat(check.reason(request(headers("Authorization", "bearer " + token(""))), config(true))).isNull();
        assertThat(check.reason(request(headers("Authorization", "Bearer ")), config(true))).contains("no api-key");
        assertThat(check.reason(request(headers("Authorization", "Bearer")), config(true))).contains("no api-key");
        // api-key and Basic together: the api-key is the key, the Basic password is never tried
        assertThat(check.reason(request(headers("api-key", STATIC, "Authorization", basic("wrong-0123456789abcdef0123456789"))), config(true)))
                .isNull();
        assertThat(check.reason(request(headers("api-key", "wrong-0123456789abcdef0123456789", "Authorization", basic(STATIC))), config(true)))
                .isEqualTo("api key not found");
        // a Basic password against a command with GitLab keys only
        IApiKeys jwtOnly = config(true);
        jwtOnly.getKeys().remove(0);
        assertThat(check.reason(request(headers("Authorization", basic(STATIC))), jwtOnly)).contains("no static keys");
    }

    @Test
    public void the_audit_names_the_static_key_that_authorized() throws Exception {
        CheckApiKeyAccess check = new CheckApiKeyAccess(keysDir(), CLOCK);
        Map<String, Object> attributes = new java.util.HashMap<>();

        assertThat(check.reason(request(headers("api-key", STATIC), attributes), config(true))).isNull();

        assertThat(attributes.get(com.payneteasy.dcagent.jetty.AuditFilter.AUTHORIZED_BY)).asString()
                .startsWith("static key ci-static ****").doesNotContain(STATIC);
    }

    @Test
    public void a_rule_mismatch_or_a_missing_issuer_file_refuses_the_token() throws Exception {
        Path keys = keysDir();
        CheckApiKeyAccess check = new CheckApiKeyAccess(keys, CLOCK);
        String unprotected = TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW - 60, "").replace("\"ref_protected\":\"true\"", "\"ref_protected\":\"false\""));

        assertThat(check.reason(request(headers("Authorization", "Bearer " + unprotected)), config(true))).contains("ref_protected");
        Files.delete(keys.resolve("gitlab-test.json"));
        assertThat(check.reason(request(headers("Authorization", "Bearer " + token(""))), config(true))).contains("missing");
        assertThat(new CheckApiKeyAccess(null, CLOCK).reason(request(headers("Authorization", "Bearer " + token(""))), config(true)))
                .contains("no keys directory");
    }

    @Test
    public void fetch_jwks_creates_then_refreshes_the_issuer_file_from_discovery() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/git";
        String[] discoveryIssuer = {issuer};
        server.createContext("/git/.well-known/openid-configuration", exchange -> {
            byte[] body = ("{\"issuer\":\"" + discoveryIssuer[0] + "\",\"jwks_uri\":\"" + issuer + "/oauth/discovery/keys\"}").getBytes(UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        String[] servedKid = {"k1"};
        server.createContext("/git/oauth/discovery/keys", exchange -> {
            byte[] body = ("{\"keys\":[" + TOKENS.jwk(servedKid[0]) + "]}").getBytes(UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (java.net.http.HttpClient client = com.payneteasy.dcagent.core.auth.gitlab.JwksFetcher.client()) {
            Path keys = tmp.newFolder("fetched").toPath();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream print = new PrintStream(out, true, UTF_8);

            assertThat(DcAgentApplication.fetchJwks(keys, "gitlab", null, client, CLOCK, print))
                    .isEqualTo(1);
            assertThat(DcAgentApplication.fetchJwks(keys, "gitlab", issuer, client, CLOCK, print))
                    .isZero();
            Path file = keys.resolve("gitlab.json");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-r--r--");
            assertThat(com.payneteasy.dcagent.core.auth.gitlab.IssuerFiles.load(keys, "gitlab").keys()).containsOnlyKeys("k1");

            // a refresh keeps the pinned issuer; another --issuer is refused; a discovery naming someone else too
            assertThat(DcAgentApplication.fetchJwks(keys, "gitlab", null, client, CLOCK, print))
                    .isZero();
            assertThat(DcAgentApplication.fetchJwks(keys, "gitlab", "http://127.0.0.1:1/other", client, CLOCK, print))
                    .isEqualTo(1);
            discoveryIssuer[0] = "http://evil.test/git";
            servedKid[0] = "k2";
            String before = Files.readString(file);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    DcAgentApplication.fetchJwks(keys, "gitlab", null, client, CLOCK, print))
                    .hasMessageContaining("another issuer");
            assertThat(Files.readString(file)).isEqualTo(before).doesNotContain("\"k2\"");
            assertThat(out.toString(UTF_8)).contains("does not exist").contains("pins ");
        } finally {
            server.stop(0);
        }
    }
}
