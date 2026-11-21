package com.payneteasy.dcagent.jetty;

import com.payneteasy.dcagent.TestConfigs;

import com.payneteasy.dcagent.core.config.model.IApiKeys;
import com.payneteasy.dcagent.core.exception.WrongApiKeyException;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CheckApiKeyTest {

    private final CheckApiKey checkApiKey = new CheckApiKey();

    private static HttpServletRequest requestWith(Map<String, String> headers) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                CheckApiKeyTest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, method, args) ->
                        "getHeader".equals(method.getName()) ? headers.get(args[0]) : null);
    }

    private static IApiKeys keys(String... allowed) {
        Map<String, String> map = new HashMap<>();
        for (String key : allowed) {
            map.put(TestConfigs.hash(key), "x");
        }
        return () -> map;
    }

    @Test
    public void parse_basic_auth_returns_password() {
        assertThat(CheckApiKey.parseBasisAuth("Basic QWxhZGRpbjpPcGVuU2VzYW1l")).isEqualTo("OpenSesame");
    }

    @Test
    public void parse_basic_auth_of_malformed_header_without_space_is_null() {
        assertThat(CheckApiKey.parseBasisAuth("Basic")).isNull();
    }

    @Test
    public void parse_basic_auth_without_colon_is_null() {
        // base64("nocolon") = bm9jb2xvbg==
        assertThat(CheckApiKey.parseBasisAuth("Basic bm9jb2xvbg==")).isNull();
    }

    @Test
    public void check_accepts_a_known_api_key_header() {
        HttpServletRequest request = requestWith(Map.of("api-key", "secret"));

        assertThatCode(() -> checkApiKey.check(request, keys("secret"))).doesNotThrowAnyException();
    }

    @Test
    public void check_accepts_a_key_from_basic_authorization() {
        HttpServletRequest request = requestWith(Map.of("Authorization", "Basic QWxhZGRpbjpPcGVuU2VzYW1l"));

        assertThatCode(() -> checkApiKey.check(request, keys("OpenSesame"))).doesNotThrowAnyException();
    }

    @Test
    public void check_rejects_a_request_without_credentials() {
        assertThatThrownBy(() -> checkApiKey.check(requestWith(Map.of()), keys("secret")))
                .isInstanceOf(WrongApiKeyException.class);
    }

    @Test
    public void check_rejects_when_no_api_keys_are_configured() {
        HttpServletRequest request = requestWith(Map.of("api-key", "secret"));

        assertThatThrownBy(() -> checkApiKey.check(request, () -> null))
                .isInstanceOf(WrongApiKeyException.class);
    }

    @Test
    public void check_rejects_an_unknown_api_key() {
        HttpServletRequest request = requestWith(Map.of("api-key", "wrong"));

        assertThatThrownBy(() -> checkApiKey.check(request, keys("secret")))
                .isInstanceOf(WrongApiKeyException.class);
    }

    @Test
    public void check_rejects_malformed_base64_as_unauthorized() {
        HttpServletRequest request = requestWith(Map.of("Authorization", "Basic %%%not-base64%%%"));

        assertThatThrownBy(() -> checkApiKey.check(request, keys("secret")))
                .isInstanceOf(WrongApiKeyException.class)
                .hasMessage(CheckApiKey.UNAUTHORIZED);
    }

    @Test
    public void password_may_contain_a_colon() {
        // base64("user:pa:ss") = dXNlcjpwYTpzcw==
        assertThat(CheckApiKey.parseBasisAuth("Basic dXNlcjpwYTpzcw==")).isEqualTo("pa:ss");

        HttpServletRequest request = requestWith(Map.of("Authorization", "Basic dXNlcjpwYTpzcw=="));
        assertThatCode(() -> checkApiKey.check(request, keys("pa:ss"))).doesNotThrowAnyException();
    }

    @Test
    public void basic_scheme_is_case_insensitive() {
        assertThat(CheckApiKey.parseBasisAuth("basic QWxhZGRpbjpPcGVuU2VzYW1l")).isEqualTo("OpenSesame");
    }

    @Test
    public void check_rejects_a_bearer_token_even_if_it_decodes_to_a_known_key() {
        // base64("user:good-key") = dXNlcjpnb29kLWtleQ== — accepted only under the Basic scheme
        HttpServletRequest basic = requestWith(Map.of("Authorization", "Basic dXNlcjpnb29kLWtleQ=="));
        assertThatCode(() -> checkApiKey.check(basic, keys("good-key"))).doesNotThrowAnyException();

        HttpServletRequest bearer = requestWith(Map.of("Authorization", "Bearer dXNlcjpnb29kLWtleQ=="));
        assertThatThrownBy(() -> checkApiKey.check(bearer, keys("good-key")))
                .isInstanceOf(WrongApiKeyException.class)
                .hasMessage(CheckApiKey.UNAUTHORIZED);
    }

    @Test
    public void check_rejects_empty_api_keys() {
        HttpServletRequest request = requestWith(Map.of("api-key", "secret"));

        assertThatThrownBy(() -> checkApiKey.check(request, Map::of))
                .isInstanceOf(WrongApiKeyException.class);
    }

    @Test
    public void every_rejection_has_the_same_message() {
        HttpServletRequest noKey  = requestWith(Map.of());
        HttpServletRequest badKey = requestWith(Map.of("api-key", "wrong"));
        HttpServletRequest good   = requestWith(Map.of("api-key", "secret"));

        assertThatThrownBy(() -> checkApiKey.check(noKey, keys("secret"))).hasMessage(CheckApiKey.UNAUTHORIZED);
        assertThatThrownBy(() -> checkApiKey.check(badKey, keys("secret"))).hasMessage(CheckApiKey.UNAUTHORIZED);
        assertThatThrownBy(() -> checkApiKey.check(good, () -> null)).hasMessage(CheckApiKey.UNAUTHORIZED);
    }

    @Test
    public void the_stored_hash_sent_as_a_key_is_rejected() {
        CheckApiKey check = new CheckApiKey();

        assertThat(check.rejectReason(requestWith(Map.of("api-key", "secret")), keys("secret"))).isNull();
        assertThat(check.rejectReason(requestWith(Map.of("api-key", TestConfigs.hash("secret"))), keys("secret")))
                .isEqualTo("api key not found");
    }

    @Test
    public void a_plaintext_entry_never_matches_and_the_hashed_ones_still_do() {
        Map<String, String> mixed = new HashMap<>();
        mixed.put(TestConfigs.hash("hashed-key"), "ci");
        mixed.put("plain-key", "old");
        CheckApiKey check = new CheckApiKey();

        assertThat(check.rejectReason(requestWith(Map.of("api-key", "hashed-key")), () -> mixed)).isNull();
        assertThat(check.rejectReason(requestWith(Map.of("api-key", "plain-key")), () -> mixed))
                .contains("1 plaintext key(s)").doesNotContain("plain-key");
    }

    private static IApiKeys typedKeys(com.payneteasy.dcagent.core.config.model.TCommandKey... aKeys) {
        return new IApiKeys() {
            @Override
            public Map<String, String> getApiKeys() {
                return null;
            }

            @Override
            public java.util.List<com.payneteasy.dcagent.core.config.model.TCommandKey> getKeys() {
                return java.util.List.of(aKeys);
            }
        };
    }

    @Test
    public void a_sha256_entry_of_keys_accepts_its_key_and_nothing_else() {
        IApiKeys keys = typedKeys(com.payneteasy.dcagent.core.config.model.TCommandKey.builder()
                .name("ci").type(com.payneteasy.dcagent.core.config.model.TKeyType.SHA256).hash(TestConfigs.hash("secret")).build());

        assertThat(checkApiKey.rejectReason(requestWith(Map.of("api-key", "secret")), keys)).isNull();
        assertThat(checkApiKey.rejectReason(requestWith(Map.of("api-key", "other")), keys)).isEqualTo("api key not found");
    }

    @Test
    public void a_gitlab_entry_is_not_a_static_key() {
        IApiKeys keys = typedKeys(com.payneteasy.dcagent.core.config.model.TCommandKey.builder()
                .name("gitlab").type(com.payneteasy.dcagent.core.config.model.TKeyType.GITLAB_JWT)
                .hash(TestConfigs.hash("secret")).build());

        // even a hash put on a GITLAB_JWT entry by mistake never authorizes a static key
        assertThat(checkApiKey.rejectReason(requestWith(Map.of("api-key", "secret")), keys)).contains("no static keys");
    }
}
