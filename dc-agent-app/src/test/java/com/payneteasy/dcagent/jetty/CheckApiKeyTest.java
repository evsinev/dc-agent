package com.payneteasy.dcagent.jetty;

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
            map.put(key, "x");
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
}
