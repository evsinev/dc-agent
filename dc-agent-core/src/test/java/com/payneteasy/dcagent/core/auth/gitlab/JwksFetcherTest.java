package com.payneteasy.dcagent.core.auth.gitlab;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** fetch-jwks against a local issuer: what it keeps, leaves out and refuses. */
public class JwksFetcherTest {

    private static final TestTokens TOKENS = new TestTokens(2048);
    private static final Instant    NOW    = Instant.parse("2026-11-21T03:00:00Z");
    private static final String     EC_KEY = "{\"kty\":\"EC\",\"kid\":\"ec-1\",\"crv\":\"P-256\",\"x\":\"AA\",\"y\":\"AA\","
            + "\"alg\":\"ES256\",\"use\":\"sig\"}";

    private HttpServer server;
    private String     issuer;
    private String     discovery;
    private String     jwks;
    private int        jwksStatus;

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/git";
        discovery = "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer + "/oauth/discovery/keys\"}";
        jwks = "{\"keys\":[" + TOKENS.jwk("k1") + "]}";
        jwksStatus = 200;
        server.createContext("/git/.well-known/openid-configuration", exchange -> {
            byte[] body = discovery.getBytes(UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/git/oauth/discovery/keys", exchange -> {
            byte[] body = jwks.getBytes(UTF_8);
            if (jwksStatus == 302) {
                exchange.getResponseHeaders().add("Location", issuer + "/elsewhere");
            }
            exchange.sendResponseHeaders(jwksStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private JsonObject fetch(List<String> aSkipped) throws Exception {
        try (HttpClient client = JwksFetcher.client()) {
            return JwksFetcher.fetch("gitlab", issuer, client, NOW, aSkipped);
        }
    }

    private void refused(String aReason) {
        assertThatThrownBy(() -> fetch(new ArrayList<>())).isInstanceOf(JwtRejectedException.class).hasMessageContaining(aReason);
    }

    @Test
    public void keys_the_agent_cannot_use_are_left_out_and_certificate_references_dropped() throws Exception {
        jwks = "{\"keys\":[" + EC_KEY + "," + TOKENS.jwk("k1").replace("\"use\":\"sig\"", "\"use\":\"sig\",\"x5t\":\"abc\"") + "]}";
        List<String> skipped = new ArrayList<>();

        JsonObject file = fetch(skipped);

        assertThat(IssuerFiles.parse("gitlab", file).keys()).containsOnlyKeys("k1");
        assertThat(file.toString()).doesNotContain("ec-1").doesNotContain("x5t");
        assertThat(skipped.get(0)).contains("key ec-1: not an RS256 signing key (kty EC, alg ES256, use sig)");
        assertThat(skipped).hasSize(1);
    }

    @Test
    public void a_restriction_the_agent_does_not_understand_leaves_the_key_out_never_dropped() throws Exception {
        String k1 = TOKENS.jwk("k1");
        jwks = "{\"keys\":[" + k1.replace("\"kid\":\"k1\"", "\"kid\":\"ops-sign\",\"key_ops\":[\"sign\"]") + ","
                + k1.replace("\"kid\":\"k1\"", "\"kid\":\"ext\",\"ext\":false") + ","
                + k1.replace("\"kid\":\"k1\"", "\"kid\":\"ops-verify\",\"key_ops\":[\"verify\"]") + "]}";
        List<String> skipped = new ArrayList<>();

        JsonObject file = fetch(skipped);

        assertThat(IssuerFiles.parse("gitlab", file).keys()).containsOnlyKeys("ops-verify");
        assertThat(file.toString()).doesNotContain("key_ops");
        assertThat(skipped).containsExactly("key ops-sign: key_ops does not allow verify", "key ext: unknown fields [ext]");
    }

    @Test
    public void private_key_material_refuses_the_whole_jwks() {
        jwks = "{\"keys\":[" + TOKENS.jwk("k1").replace("\"use\":\"sig\"", "\"use\":\"sig\",\"d\":\"AQAB\"") + "]}";
        refused("private key material");
        jwks = "{\"keys\":[" + TOKENS.jwk("k1") + "," + EC_KEY.replace("\"y\":\"AA\"", "\"y\":\"AA\",\"d\":\"AA\"") + "]}";
        refused("private key material");
    }

    @Test
    public void a_jwks_without_a_usable_key_writes_nothing() {
        jwks = "{\"keys\":[" + EC_KEY + "]}";
        refused("no keys");
    }

    @Test
    public void a_usable_key_that_is_broken_still_refuses_the_set() {
        jwks = "{\"keys\":[" + new TestTokens(1024).jwk("short") + "]}";
        refused("shorter than 2048");
    }

    @Test
    public void the_discovery_must_name_the_issuer_and_a_jwks_uri_on_its_origin() {
        String good = discovery;
        discovery = good.replace("\"issuer\":\"" + issuer, "\"issuer\":\"https://other.test/git");
        refused("another issuer");
        discovery = good.replace(issuer + "/oauth", "http://localhost:" + server.getAddress().getPort() + "/git/oauth");
        refused("outside the issuer's origin");
        discovery = good.replace(issuer + "/oauth", "http://127.0.0.1:1/git/oauth");
        refused("outside the issuer's origin");
        discovery = good.replace(issuer + "/oauth", "https://127.0.0.1:" + server.getAddress().getPort() + "/git/oauth");
        refused("outside the issuer's origin");
        discovery = good.replace("\"jwks_uri\":\"" + issuer + "/oauth/discovery/keys\"", "\"jwks_uri\":1");
        refused("no jwks_uri");
    }

    @Test
    public void a_redirect_another_status_or_an_oversized_jwks_is_refused() {
        jwksStatus = 302;
        refused("answered 302");
        jwksStatus = 500;
        refused("answered 500");
        jwksStatus = 200;
        jwks = "{\"keys\":[" + TOKENS.jwk("k1") + "],\"pad\":\"" + "x".repeat((int) IssuerFiles.MAX_BYTES) + "\"}";
        refused("larger than");
    }

    @Test
    public void same_origin_compares_scheme_host_and_port() {
        assertThat(JwksFetcher.sameOrigin("https://code.test/git", "https://CODE.test:443/git/oauth/discovery/keys")).isTrue();
        assertThat(JwksFetcher.sameOrigin("https://code.test/git", "https://code.test.evil/keys")).isFalse();
        assertThat(JwksFetcher.sameOrigin("https://code.test/git", "https://code.test:8443/keys")).isFalse();
        assertThat(JwksFetcher.sameOrigin("https://code.test/git", "http://code.test/keys")).isFalse();
        assertThat(JwksFetcher.sameOrigin("https://code.test/git", "https://user@code.test/keys")).isFalse();
        assertThat(JwksFetcher.sameOrigin("https://code.test/git", "not a uri")).isFalse();
    }
}
