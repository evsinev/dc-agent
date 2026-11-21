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
import java.util.List;

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
            app.releaseConfigLock();
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
        assertThat(good.toString(UTF_8)).contains("OK: ").contains("At start the agent will rewrite site.json: 1 plaintext key(s) hashed");

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
    public void hash_key_reads_stdin_and_prints_the_stored_form() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String key = "a-ci-key-0123456789abcdef0123456789";

        int code = DcAgentApplication.hashKey(new java.io.ByteArrayInputStream((key + "\n").getBytes(UTF_8)),
                new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8));

        assertThat(code).isZero();
        assertThat(out.toString(UTF_8).trim()).isEqualTo(com.payneteasy.dcagent.core.config.validation.ApiKeyHash.hash(key));
    }

    @Test
    public void hash_key_refuses_a_short_key() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = DcAgentApplication.hashKey(new java.io.ByteArrayInputStream("short\n".getBytes(UTF_8)),
                new PrintStream(out, true, UTF_8), new PrintStream(new ByteArrayOutputStream(), true, UTF_8));

        assertThat(code).isEqualTo(1);
        assertThat(out.toString(UTF_8)).isEmpty();
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

    @Test
    public void the_start_hashes_the_keys_once_and_a_second_start_changes_no_byte() throws Exception {
        Path dir = configDir();
        Files.writeString(dir.resolve("site.json"), GOOD);
        Files.writeString(dir.resolve("menu.yml"), "type: ZIP_ARCHIVE\napiKeys:\n  menu-key: ci\ndir: /opt/menu\n");

        startAndStop(dir);
        String site = Files.readString(dir.resolve("site.json"));
        String menu = Files.readString(dir.resolve("menu.json"));
        assertThat(site).contains(com.payneteasy.dcagent.core.config.validation.ApiKeyHash.hash("k")).doesNotContain("\"k\"");
        assertThat(menu).contains(com.payneteasy.dcagent.core.config.validation.ApiKeyHash.hash("menu-key")).doesNotContain("menu-key\"");
        assertThat(dir.resolve("menu.yml")).doesNotExist();

        startAndStop(dir);
        assertThat(Files.readString(dir.resolve("site.json"))).isEqualTo(site);
        assertThat(Files.readString(dir.resolve("menu.json"))).isEqualTo(menu);
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder("site.json", "menu.json", DcAgentApplication.CONFIG_LOCK);
        }
    }

    @Test
    public void a_bad_config_dir_is_not_touched_at_all() throws Exception {
        Path dir = configDir();
        Files.writeString(dir.resolve("site.json"), GOOD);
        Files.writeString(dir.resolve("typo.json"), GOOD.replace("ZIP_ARCHIVE", "ZIP_ARHCIVE"));

        assertThatThrownBy(() -> new DcAgentApplication().start(config(), dir.toFile())).isInstanceOf(IllegalStateException.class);

        assertThat(Files.readString(dir.resolve("site.json"))).isEqualTo(GOOD);
        assertThat(dir.resolve(DcAgentApplication.CONFIG_LOCK)).doesNotExist();
    }

    @Test
    public void a_malformed_hash_refuses_the_start_and_is_never_hashed_again() throws Exception {
        Path   dir       = configDir();
        String malformed = GOOD.replace("\"k\"", "\"SHA256:" + "A".repeat(64) + "\"");
        Files.writeString(dir.resolve("site.json"), malformed);

        assertThatThrownBy(() -> new DcAgentApplication().start(config(), dir.toFile()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("starts with sha256: but");
        assertThat(Files.readString(dir.resolve("site.json"))).isEqualTo(malformed);
    }

    @Test
    public void the_migration_keeps_the_mode_of_the_config() throws Exception {
        Path dir  = configDir();
        Path site = Files.writeString(dir.resolve("site.json"), GOOD);
        Files.setPosixFilePermissions(site, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));

        startAndStop(dir);

        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(site))).isEqualTo("rw-r-----");
    }

    @Test
    public void a_second_agent_on_the_same_config_dir_refuses_to_start() throws Exception {
        Path dir = configDir();
        Files.writeString(dir.resolve("site.json"), GOOD);
        DcAgentApplication first = new DcAgentApplication();
        first.start(config(), dir.toFile());
        try {
            assertThatThrownBy(() -> new DcAgentApplication().start(config(), dir.toFile()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("another dc-agent");
        } finally {
            first.jetty().stop();
            first.releaseConfigLock();
        }
    }

    @Test
    public void check_config_reports_the_migration_and_writes_nothing() throws Exception {
        Path dir = configDir();
        Files.writeString(dir.resolve("site.json"), GOOD);
        Files.writeString(dir.resolve("menu.yml"), "type: ZIP_ARCHIVE\napiKeys:\n  menu-key: ci\ndir: /opt/menu\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(DcAgentApplication.checkConfig(config(), dir, new PrintStream(out, true, UTF_8))).isZero();

        assertThat(out.toString(UTF_8)).contains("menu.yml: 1 plaintext key(s) hashed, written as menu.json");
        assertThat(Files.readString(dir.resolve("site.json"))).isEqualTo(GOOD);
        assertThat(dir.resolve("menu.yml")).exists();
        assertThat(dir.resolve("menu.json")).doesNotExist();
        assertThat(dir.resolve(DcAgentApplication.CONFIG_LOCK)).doesNotExist();
    }

    @Test
    public void a_backup_does_not_drop_the_lock_for_another_process() throws Exception {
        Path dir = configDir();
        Files.writeString(dir.resolve("site.json"), GOOD);
        DcAgentApplication app = new DcAgentApplication();
        app.start(config(), dir.toFile());
        try {
            List<String> backedUp = new com.payneteasy.dcagent.controlplane.service.command.ConfigBackupService(dir.toFile())
                    .listConfigFiles().stream().map(entry -> entry.getName()).toList();
            assertThat(backedUp).containsExactly("site.json");

            // fcntl locks are per process: only another process sees whether it is still held
            assertThat(tryLockInAnotherProcess(dir.resolve(DcAgentApplication.CONFIG_LOCK))).isEqualTo("locked");

            // twin: reading the lock file here, as the backup did, really drops the lock
            Files.readAllBytes(dir.resolve(DcAgentApplication.CONFIG_LOCK));
            assertThat(tryLockInAnotherProcess(dir.resolve(DcAgentApplication.CONFIG_LOCK))).isEqualTo("free");
        } finally {
            app.jetty().stop();
            app.releaseConfigLock();
        }
        assertThat(tryLockInAnotherProcess(dir.resolve(DcAgentApplication.CONFIG_LOCK))).isEqualTo("free");
    }

    @Test
    public void a_migration_that_would_outgrow_the_size_limit_writes_nothing() throws Exception {
        Path          dir  = configDir();
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < 14_000; i++) {
            keys.append(i == 0 ? "" : ",").append("\"").append(String.format("key-%028d", i)).append("\":\"ci\"");
        }
        String big = "{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{" + keys + "},\"dir\":\"/opt/site\"}";
        Files.writeString(dir.resolve("site.json"), big);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(DcAgentApplication.checkConfig(config(), dir, new PrintStream(out, true, UTF_8))).isEqualTo(1);
        assertThat(out.toString(UTF_8)).contains("with its keys hashed it would be");

        assertThatThrownBy(() -> new DcAgentApplication().start(config(), dir.toFile()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("with its keys hashed it would be");
        assertThat(Files.readString(dir.resolve("site.json"))).isEqualTo(big);
    }

    private String tryLockInAnotherProcess(Path aLock) throws Exception {
        Path source = tmp.newFolder().toPath().resolve("TryLock.java");
        Files.writeString(source, String.join("\n",
                "import java.nio.channels.FileChannel;",
                "import java.nio.file.*;",
                "public class TryLock {",
                "  public static void main(String[] a) throws Exception {",
                "    try (FileChannel c = FileChannel.open(Path.of(a[0]), StandardOpenOption.WRITE)) {",
                "      System.out.print(c.tryLock() == null ? \"locked\" : \"free\");",
                "    }",
                "  }",
                "}"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, source.toString(), aLock.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8).trim();
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

    @Test
    public void migrate_keys_shows_then_writes_but_never_under_a_running_agent() throws Exception {
        Path dir = tmp.newFolder("migrate").toPath();
        String hash = TestConfigs.hash("first-key-0123456789abcdef0123456789");
        String legacy = "{\"type\":\"ZIP_ARCHIVE\",\"apiKeys\":{\"" + hash + "\":\"gitlab-ci\"},\"dir\":\"/opt/site\"}";
        Files.writeString(dir.resolve("site.json"), legacy, UTF_8);

        ByteArrayOutputStream dry = new ByteArrayOutputStream();
        assertThat(DcAgentApplication.migrateKeys(config(), dir, false, new PrintStream(dry, true, UTF_8))).isZero();
        assertThat(dry.toString(UTF_8)).contains("WOULD WRITE site.json: 1 key(s) apiKeys -> keys");
        assertThat(Files.readString(dir.resolve("site.json"))).isEqualTo(legacy);

        DcAgentApplication app = new DcAgentApplication();
        app.start(config(), dir.toFile());
        try {
            ByteArrayOutputStream running = new ByteArrayOutputStream();
            assertThat(DcAgentApplication.migrateKeys(config(), dir, true, new PrintStream(running, true, UTF_8))).isEqualTo(1);
            assertThat(running.toString(UTF_8)).contains("the agent is running");
            assertThat(Files.readString(dir.resolve("site.json"))).isEqualTo(legacy);
        } finally {
            app.jetty().stop();
            app.releaseConfigLock();
        }

        ByteArrayOutputStream write = new ByteArrayOutputStream();
        assertThat(DcAgentApplication.migrateKeys(config(), dir, true, new PrintStream(write, true, UTF_8))).isZero();
        assertThat(Files.readString(dir.resolve("site.json"))).contains("\"keys\"").contains(hash).doesNotContain("apiKeys");
        ByteArrayOutputStream check = new ByteArrayOutputStream();
        assertThat(DcAgentApplication.checkConfig(config(), dir, new PrintStream(check, true, UTF_8))).isZero();
    }

    @Test
    public void check_config_warns_about_a_wide_gitlab_rule_and_still_passes() throws Exception {
        Path dir = tmp.newFolder("warn").toPath();
        Files.writeString(dir.resolve("site.json"), "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"/opt/site\",\"keys\":[{\"name\":\"gitlab-ci\","
                + "\"type\":\"GITLAB_JWT\",\"issuer\":\"code-clubber-me\",\"audience\":\"dc-agent:host\",\"rules\":[{\"project_id\":913}]}]}", UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(DcAgentApplication.checkConfig(config(), dir, new PrintStream(out, true, UTF_8))).isZero();
        assertThat(out.toString(UTF_8)).contains("Warning: site.json: keys[0] (gitlab-ci).rules[0]: no ref_protected")
                .contains("no pipeline_source").contains("OK:");
    }

    private static void startAndStop(Path aDir) throws Exception {
        DcAgentApplication app = new DcAgentApplication();
        app.start(config(), aDir.toFile());
        app.jetty().stop();
        app.releaseConfigLock();
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
