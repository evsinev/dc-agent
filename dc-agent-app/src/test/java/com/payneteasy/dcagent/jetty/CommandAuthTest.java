package com.payneteasy.dcagent.jetty;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.service.impl.ConfigServiceImpl;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.exception.WrongApiKeyException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CommandAuthTest {

    private final CommandAuth auth = new CommandAuth();

    private final List<String> loaded = new ArrayList<>();

    private static HttpServletRequest requestWith(Map<String, String> aHeaders) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                CommandAuthTest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, method, args) ->
                        "getHeader".equals(method.getName()) ? aHeaders.get(args[0]) : null);
    }

    private static HttpServletRequest withKey(String aKey) {
        return requestWith(Map.of("api-key", aKey));
    }

    private static TJarConfig config(TaskType aType, String... aKeys) {
        Map<String, String> keys = new java.util.HashMap<>();
        for (String key : aKeys) {
            keys.put(key, "owner");
        }
        return TJarConfig.builder().type(aType).apiKeys(keys).build();
    }

    private Function<String, TJarConfig> loader(TJarConfig aConfig) {
        return name -> {
            loaded.add(name);
            return aConfig;
        };
    }

    private void assertUnauthorized(HttpServletRequest aRequest, String aName, Function<String, TJarConfig> aLoader) {
        assertThatThrownBy(() -> auth.authorize(aRequest, aName, TaskType.JAR, aLoader))
                .isInstanceOf(WrongApiKeyException.class)
                .hasMessage(CheckApiKey.UNAUTHORIZED);
    }

    @Test
    public void good_key_name_and_type_pass_and_return_the_config() {
        TJarConfig config = config(TaskType.JAR, "secret");

        assertThat(auth.authorize(withKey("secret"), "billing", TaskType.JAR, loader(config))).isSameAs(config);
        assertThat(loaded).containsExactly("billing");
    }

    @Test
    public void no_key_is_rejected_without_loading_the_config() {
        assertUnauthorized(requestWith(Map.of()), "billing", loader(config(TaskType.JAR, "secret")));
        assertUnauthorized(requestWith(Map.of("Authorization", "Bearer whatever")), "billing",
                loader(config(TaskType.JAR, "secret")));
        assertThat(loaded).isEmpty();
    }

    @Test
    public void bad_name_is_rejected_without_loading_the_config() {
        for (String name : new String[]{"..", "a..b", "a/b", "", "a b", null}) {
            assertUnauthorized(withKey("secret"), name, loader(config(TaskType.JAR, "secret")));
        }
        assertThat(loaded).isEmpty();
    }

    @Test
    public void names_the_control_plane_creates_reach_the_loader() {
        String longName = "a".repeat(200);
        for (String name : new String[]{"_app", ".app", "a-b.c_d", longName}) {
            auth.authorize(withKey("secret"), name, TaskType.JAR, loader(config(TaskType.JAR, "secret")));
        }
        assertThat(loaded).containsExactly("_app", ".app", "a-b.c_d", longName);
    }

    @Test
    public void loader_failure_is_the_same_unauthorized() {
        assertUnauthorized(withKey("secret"), "missing", name -> {
            throw new IllegalStateException("No any files /abs/config/missing.json or /abs/config/missing.yml found");
        });
    }

    @Test
    public void loader_returning_null_is_the_same_unauthorized() {
        assertUnauthorized(withKey("secret"), "empty", name -> null);
    }

    @Test
    public void config_without_type_passes_with_the_right_key() {
        TJarConfig config = config(null, "secret");

        assertThat(auth.authorize(withKey("secret"), "legacy", TaskType.JAR, loader(config))).isSameAs(config);
    }

    @Test
    public void config_without_type_still_needs_the_right_key() {
        assertUnauthorized(withKey("wrong"), "legacy", loader(config(null, "secret")));
    }

    @Test
    public void config_of_another_type_is_rejected_even_with_the_right_key() {
        assertUnauthorized(withKey("secret"), "billing", loader(config(TaskType.SAVE_ARTIFACT, "secret")));
    }

    @Test
    public void wrong_key_or_no_api_keys_is_rejected() {
        assertUnauthorized(withKey("wrong"), "billing", loader(config(TaskType.JAR, "secret")));
        assertUnauthorized(withKey("secret"), "billing", loader(TJarConfig.builder().type(TaskType.JAR).build()));
    }

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void loader_failure_does_not_put_the_parser_message_with_keys_into_the_log() throws IOException {
        Path dir = tmp.getRoot().toPath();
        // Gson: "duplicate key: secret-demo"; the YAML parser may quote the broken line
        Files.writeString(dir.resolve("dup.json"), "{\"type\":\"JAR\",\"apiKeys\":{\"secret-demo\":\"a\",\"secret-demo\":\"b\"}}");
        Files.writeString(dir.resolve("bad.yml"), "type: JAR\napiKeys:\n  secret-yaml: [ci\n  : {\n");
        ConfigServiceImpl configService = new ConfigServiceImpl(dir.toFile(), Gsons.PRETTY_GSON);

        Logger                     logger   = (Logger) LoggerFactory.getLogger(CommandAuth.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertUnauthorized(withKey("wrong"), "dup", configService::getJarConfig);
            assertUnauthorized(withKey("wrong"), "bad", configService::getJarConfig);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(2);
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).contains("cannot load config").doesNotContain("secret");
        }
    }

    @Test
    public void type_chain_has_class_names_only() {
        IllegalStateException error = new IllegalStateException("Cannot parse /abs/x.yml",
                new RuntimeException("duplicate key: secret-demo"));

        assertThat(CommandAuth.typeChain(error)).isEqualTo("IllegalStateException <- RuntimeException");
    }
}
