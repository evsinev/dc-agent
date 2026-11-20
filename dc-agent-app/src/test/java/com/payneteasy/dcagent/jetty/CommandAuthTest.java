package com.payneteasy.dcagent.jetty;

import com.payneteasy.dcagent.TestConfigs;

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
            keys.put(TestConfigs.hash(key), "owner");
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
    public void config_without_type_is_refused_even_with_the_right_key() {
        // twin: good_key_name_and_type_pass_and_return_the_config — the same config with type JAR
        assertUnauthorized(withKey("secret"), "legacy", loader(config(null, "secret")));
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
        ConfigServiceImpl configService = new ConfigServiceImpl(dir.toFile(), Gsons.PRETTY_GSON, TestConfigs.VALIDATOR);

        Logger                     logger   = (Logger) LoggerFactory.getLogger(CommandAuth.class);
        Logger                     loader   = (Logger) LoggerFactory.getLogger(ConfigServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        loader.addAppender(appender);
        try {
            assertUnauthorized(withKey("wrong"), "dup", configService::getJarConfig);
            assertUnauthorized(withKey("wrong"), "bad", configService::getJarConfig);
        } finally {
            logger.detachAppender(appender);
            loader.detachAppender(appender);
        }

        // per config: the loader's problems, then the auth step's refusal
        assertThat(appender.list).hasSize(4);
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain("secret");
        }
        assertThat(appender.list).filteredOn(event -> event.getLoggerName().equals(CommandAuth.class.getName()))
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).contains("cannot load config"));
    }

    private static final String RIGHT_KEY = "right-key-0123456789abcdef0123456789";

    /**
     * The config-level refusals with the right key in the request — each 401 comes from the rule,
     * not from a missing or wrong key: the first config differs from the others only in that rule.
     */
    @Test
    public void config_rules_refuse_on_a_call_even_with_the_right_key() throws IOException {
        Path   dir  = tmp.getRoot().toPath();
        String keys = "\"apiKeys\":{\"" + TestConfigs.hash(RIGHT_KEY) + "\":\"ci\"},\"jarFilename\":\"/opt/app/app.jar\",\"serviceName\":\"app\",\"serviceDir\":\"" + TestConfigs.serviceDir("app") + "\"";
        Files.writeString(dir.resolve("good.json")  , "{\"type\":\"JAR\"," + keys + "}");
        Files.writeString(dir.resolve("notype.json"), "{" + keys + "}");
        Files.writeString(dir.resolve("typo.json")  , "{\"type\":\"JRA\"," + keys + "}");
        Files.writeString(dir.resolve("legacy.json"), "{\"type\":\"DOCKER_PUSH\"," + keys + "}");
        Files.writeString(dir.resolve("probe.json") , "Z2678PROBE ../dc-agent/config/zz2678probe.json");
        Files.writeString(dir.resolve("cron.json")  , "{\"type\":\"JAR\"," + keys.replace("/opt/app/app.jar", "/etc/cron.d/app.jar") + "}");
        Files.writeString(dir.resolve("twice.json") , "{\"type\":\"JAR\"," + keys + "}");
        Files.writeString(dir.resolve("twice.yml")  , "type: JAR\napiKeys:\n  '" + TestConfigs.hash(RIGHT_KEY) + "': ci\njarFilename: /opt/app/app.jar\nserviceName: app\nserviceDir: " + TestConfigs.serviceDir("app") + "\n");
        ConfigServiceImpl configService = new ConfigServiceImpl(dir.toFile(), Gsons.PRETTY_GSON, TestConfigs.VALIDATOR);

        assertThat(auth.authorize(withKey(RIGHT_KEY), "good", TaskType.JAR, configService::getJarConfig).getType())
                .isEqualTo(TaskType.JAR);
        for (String name : new String[]{"notype", "typo", "legacy", "probe", "twice", "cron"}) {
            assertUnauthorized(withKey(RIGHT_KEY), name, configService::getJarConfig);
        }
    }

    @Test
    public void type_chain_has_class_names_only() {
        IllegalStateException error = new IllegalStateException("Cannot parse /abs/x.yml",
                new RuntimeException("duplicate key: secret-demo"));

        assertThat(CommandAuth.typeChain(error)).isEqualTo("IllegalStateException <- RuntimeException");
    }

    @Test
    public void in_a_config_with_a_hash_and_a_plaintext_key_only_the_hashed_one_works() throws IOException {
        Path dir = tmp.getRoot().toPath();
        Files.writeString(dir.resolve("mixed.json"), "{\"type\":\"JAR\",\"apiKeys\":{\""
                + TestConfigs.hash(RIGHT_KEY) + "\":\"ci\",\"plain-0123456789abcdef0123456789ab\":\"old\"},"
                + "\"jarFilename\":\"/opt/app/app.jar\",\"serviceName\":\"app\",\"serviceDir\":\"" + TestConfigs.serviceDir("app") + "\"}");
        ConfigServiceImpl configService = new ConfigServiceImpl(dir.toFile(), Gsons.PRETTY_GSON, TestConfigs.VALIDATOR);

        assertThat(auth.authorize(withKey(RIGHT_KEY), "mixed", TaskType.JAR, configService::getJarConfig)).isNotNull();
        assertUnauthorized(withKey("plain-0123456789abcdef0123456789ab"), "mixed", configService::getJarConfig);
    }
}
