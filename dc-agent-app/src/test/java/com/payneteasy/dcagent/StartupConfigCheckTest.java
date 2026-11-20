package com.payneteasy.dcagent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The agent checks CONFIG_DIR before it opens the port, and check-config reports the same. */
public class StartupConfigCheckTest {

    private static final String GOOD = "{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"k\":\"ci\"},\"dir\":\"/opt/site\"}";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path configDir() throws Exception {
        return Files.createDirectories(tmp.getRoot().toPath().resolve("config"));
    }

    @Test
    public void the_agent_starts_with_a_good_config_dir() throws Exception {
        Path dir = configDir();
        Files.writeString(dir.resolve("site.json"), GOOD);

        DcAgentApplication app = new DcAgentApplication();
        try {
            app.start(config(), dir.toFile());
            assertThat(app.jetty().isStarted()).isTrue();
        } finally {
            app.jetty().stop();
        }
    }

    @Test
    public void the_agent_refuses_to_start_and_opens_no_port_with_a_bad_config() throws Exception {
        Path dir = configDir();
        Files.writeString(dir.resolve("site.json"), GOOD);
        Files.writeString(dir.resolve("i4153.json"), "{\"apiKeys\":{\"k\":\"ci\"},\"dir\":\"/root\"}");

        DcAgentApplication app = new DcAgentApplication();
        assertThatThrownBy(() -> app.start(config(), dir.toFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Refusing to start: 1 problem(s)")
                .hasMessageContaining("i4153.json: no \"type\"");
        assertThat(app.jetty()).isNull();
    }

    @Test
    public void the_agent_refuses_to_start_without_a_config_dir() {
        File absent = new File(tmp.getRoot(), "absent");

        assertThatThrownBy(() -> new DcAgentApplication().start(config(), absent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    public void check_config_exits_0_on_a_good_dir_and_1_listing_the_problems() throws Exception {
        Path dir = configDir();
        Files.writeString(dir.resolve("site.json"), GOOD);

        ByteArrayOutputStream good = new ByteArrayOutputStream();
        assertThat(DcAgentApplication.checkConfig(config(), dir, new PrintStream(good, true, UTF_8))).isZero();
        assertThat(good.toString(UTF_8)).startsWith("OK: ");

        Files.writeString(dir.resolve("typo.json"), "{\"type\":\"ZIP_ARHCIVE\",\"apiKeys\":{\"k\":\"ci\"},\"dir\":\"/opt/site\"}");
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        assertThat(DcAgentApplication.checkConfig(config(), dir, new PrintStream(bad, true, UTF_8))).isEqualTo(1);
        assertThat(bad.toString(UTF_8)).contains("1 problem(s)").contains("  - typo.json: unknown \"type\"");
    }

    @Test
    public void check_config_of_a_missing_dir_is_1_not_an_empty_ok() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThat(DcAgentApplication.checkConfig(config(), tmp.getRoot().toPath().resolve("absent"), new PrintStream(out, true, UTF_8)))
                .isEqualTo(1);
    }

    @Test
    public void a_bad_command_dirs_refuses_the_start() throws Exception {
        Path dir = configDir();
        Files.writeString(dir.resolve("site.json"), GOOD);

        assertThatThrownBy(() -> new DcAgentApplication().start(config("/"), dir.toFile()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COMMAND_DIRS");
    }

    @Test
    public void a_dir_out_of_command_dirs_refuses_the_start() throws Exception {
        Path dir = configDir();
        Files.writeString(dir.resolve("site.json"), GOOD.replace("/opt/site", "/root"));

        assertThatThrownBy(() -> new DcAgentApplication().start(config(), dir.toFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("site.json: \"dir\" /root must lie inside one of COMMAND_DIRS");
    }

    @Test
    public void an_unknown_command_is_usage_exit_2() {
        assertThat(DcAgentApplication.runCommand(new String[]{"migrate-everything"})).isEqualTo(2);
    }

    @Test
    public void config_dir_variable_set_elsewhere_refuses_the_start() {
        DcAgentApplication.checkConfigDirVariable(null, null);
        DcAgentApplication.checkConfigDirVariable("", "  ");
        DcAgentApplication.checkConfigDirVariable("/opt/dc-agent/config", "/opt/dc-agent/config/");

        assertThatThrownBy(() -> DcAgentApplication.checkConfigDirVariable("./config", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/opt/dc-agent/config");
        assertThatThrownBy(() -> DcAgentApplication.checkConfigDirVariable(null, "/etc/dc-agent"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static IStartupConfig config() {
        return config("/opt,/srv");
    }

    private static IStartupConfig config(String aCommandDirs) {
        return (IStartupConfig) Proxy.newProxyInstance(
                StartupConfigCheckTest.class.getClassLoader(),
                new Class[]{IStartupConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getJettyPort"          -> 0;
                    case "getJettyContext"       -> "/dc-agent";
                    case "getCommandDirs"        -> aCommandDirs;
                    case "getJettyIdleTimeout"   -> "10m";
                    case "getOptDir", "getTempDir", "getServicesLogDir" -> new File(System.getProperty("java.io.tmpdir"));
                    case "getServicesDefinitionDir", "getServicesDir" -> new File("/nonexistent-dc-agent-test/service");
                    case "getContainerRuntime"   -> "docker";
                    case "isControlPlaneEnabled" -> false;
                    case "getSvcCommand"         -> "/nonexistent/svc";
                    case "getSvstatCommand"      -> "/nonexistent/svstat";
                    case "appInstanceName"       -> "dc-agent-test";
                    case "appStatusToken"        -> "status-token";
                    default                      -> method.getReturnType() == boolean.class ? false
                            : method.getReturnType() == int.class ? (Object) 0 : null;
                });
    }
}
