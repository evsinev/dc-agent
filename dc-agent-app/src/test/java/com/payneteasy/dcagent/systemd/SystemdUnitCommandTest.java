package com.payneteasy.dcagent.systemd;

import com.payneteasy.dcagent.IStartupConfig;
import com.payneteasy.dcagent.core.config.validation.CommandPathPolicy;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SystemdUnitCommandTest {

    /** Not under /tmp: it is private to the unit, and the generator refuses command paths there. */
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder(new File(System.getProperty("user.dir"), "target"));

    private Path root;
    private Path home;
    private Path config;
    private Path www;
    private Path services;
    private Path definitions;
    private Path logs;

    @Before
    public void setUp() throws Exception {
        root        = tmp.getRoot().toPath().toRealPath();
        home        = Files.createDirectories(root.resolve("opt/dc-agent"));
        config      = Files.createDirectories(home.resolve("config"));
        www         = Files.createDirectories(root.resolve("srv/www"));
        services    = Files.createDirectories(root.resolve("service"));
        definitions = Files.createDirectories(root.resolve("etc/service.d"));
        logs        = Files.createDirectories(root.resolve("var/log"));
    }

    // ── drop-in ──────────────────────────────────────────────────────────

    @Test
    public void a_zip_archive_dir_is_written_and_listed_three_times() throws Exception {
        Path site = Files.createDirectories(www.resolve("site"));
        config("site", "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"" + site + "\"}");

        Result r = run("--slice", "pne.slice");

        assertThat(r.code).isZero();
        assertThat(r.err).isEmpty();
        assertThat(r.out).contains("  --rw " + site + " \\\n", "ReadWritePaths=" + site + "\n", "NoExecPaths=" + site + "\n");
        assertThat(r.out).contains("#   --rw " + site + "  — site.json: ZIP_ARCHIVE dir");
        assertThat(r.out).contains("Slice=pne.slice\n", "Environment=WEB_SERVER_INHERITED=true\n", "Environment=CONTROL_PLANE_ENABLED=false\n");
        assertThat(r.out).contains("Environment=COMMAND_DIRS=" + root.resolve("opt") + "," + root.resolve("srv") + "\n");
        assertThat(r.out).contains("ExecStart=\nExecStart=" + home.resolve("dc-agent-landlock") + " --min-abi 6 --listen-tcp 127.0.0.1:8051 \\\n");
        assertThat(r.out).contains("  -- " + home.resolve("dc-agent") + "\n");
        // the path check reads the mounts (no mount point inside a command dir)
        assertThat(r.out).contains("  --ro /proc/self/mountinfo \\\n");
        // the service roots only listed — no run script is readable for the agent
        assertThat(r.out).contains("  --rd " + services + " \\\n", "  --rd " + definitions + " \\\n");
        assertThat(r.out).doesNotContain("--ro " + services + " ");
        if (!root.startsWith("/home") && !root.startsWith("/root")) { // the build dir itself (CI: /home/runner/…)
            assertThat(r.out).doesNotContain("ProtectHome=");
        }
    }

    @Test
    public void a_jar_service_gets_its_artifact_dir_supervise_fifos_log_and_port() throws Exception {
        Path stage     = Files.createDirectories(root.resolve("opt/app/stage"));
        Path supervise = supervised(definitions.resolve("app"));
        Files.createSymbolicLink(services.resolve("app"), definitions.resolve("app"));
        Path log = Files.createDirectories(logs.resolve("app"));
        config("app", "{\"type\":\"JAR\",\"jarFilename\":\"" + stage.resolve("app.jar") + "\",\"serviceDir\":\"" + services.resolve("app")
                + "\",\"serviceLogFile\":\"" + log.resolve("current") + "\",\"waitUrl\":\"http://localhost:8083/health\"}");

        Result r = run();

        assertThat(r.err).isEmpty();
        assertThat(r.code).isZero();
        assertThat(r.out).contains("  --rw " + stage + " \\\n", "  --ro " + supervise + " \\\n",
                "  --rw " + supervise.resolve("control") + " \\\n", "  --rw " + supervise.resolve("ok") + " \\\n",
                "  --ro " + log + " \\\n", "  --connect-tcp 8083 \\\n");
        assertThat(r.out).contains("ReadWritePaths=" + stage + "\n");
        assertThat(r.out).doesNotContain("ReadWritePaths=" + supervise.resolve("control"), "NoExecPaths=" + supervise.resolve("ok"));
    }

    @Test
    public void a_zip_archive_version_lists_the_parent_of_its_dir_and_reaches_its_reload_port() throws Exception {
        Path releases = Files.createDirectories(www.resolve("releases/site"));
        config("site", "{\"type\":\"ZIP_ARCHIVE_VERSION\",\"dir\":\"" + releases + "\",\"versionFile\":\"current\","
                + "\"reloadUrl\":\"http://127.0.0.1:9090/reload?version=${version}\"}"); // the agent fills the version in

        Result r = run();

        assertThat(r.err).isEmpty();
        assertThat(r.out).contains("  --rw " + releases + " \\\n", "  --rd " + releases.getParent() + " \\\n", "  --connect-tcp 9090 \\\n");
    }

    @Test
    public void a_path_under_home_makes_home_read_only_instead_of_hidden() throws Exception {
        config("site", "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"/home/dc-agent-test-nonexistent/site\"}");

        Result r = run("--command-dirs", "/home/dc-agent-test-nonexistent," + root.resolve("srv"));

        assertThat(r.err).isEmpty();
        assertThat(r.out).contains("ProtectHome=read-only\n");
    }

    // ── refusals: nothing on stdout, the reasons on stderr, exit 1 ───────

    @Test
    public void what_the_sandbox_cannot_serve_is_refused_with_the_config_named() throws Exception {
        Path site = Files.createDirectories(www.resolve("site"));
        config("fetch", "{\"type\":\"FETCH_URL\",\"url\":\"https://example.org/x\",\"dir\":\"" + site + "\"}");
        config("push", "{\"type\":\"DOCKER\"}");
        config("far", "{\"type\":\"ZIP_ARCHIVE_VERSION\",\"dir\":\"" + www.resolve("far/v") + "\",\"versionFile\":\"current\","
                + "\"reloadUrl\":\"http://10.0.0.5/reload\"}");
        config("spaced", "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"" + www.resolve("site one") + "\"}");
        config("dollar", "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"" + www.resolve("$(id)") + "\"}");
        config("root", "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"" + root.resolve("srv") + "\"}");

        Result r = run();

        assertThat(r.code).isEqualTo(SystemdUnitCommand.REFUSED);
        assertThat(r.out).isEmpty();
        assertThat(r.err).contains("fetch.json: FETCH_URL needs outgoing network", "push.json: DOCKER needs root",
                "far.json: reloadUrl goes to 10.0.0.5", "spaced.json: the path", "dollar.json: the path",
                "root.json: " + root.resolve("srv") + " is too broad");
    }

    @Test
    public void a_command_dir_in_the_private_tmp_of_the_unit_is_refused() throws Exception {
        config("scratch", "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"/tmp/dc-agent-test-site\"}");

        Result r = run();

        assertThat(r.code).isEqualTo(SystemdUnitCommand.REFUSED);
        assertThat(r.err).contains("scratch.json: /tmp/dc-agent-test-site lies in /tmp, which is private to the unit");
    }

    @Test
    public void a_log_file_outside_the_log_root_would_open_too_much_and_is_refused() throws Exception {
        Path stage = Files.createDirectories(root.resolve("opt/app/stage"));
        supervised(services.resolve("app"));
        config("app", "{\"type\":\"JAR\",\"jarFilename\":\"" + stage.resolve("app.jar") + "\",\"serviceDir\":\"" + services.resolve("app")
                + "\",\"serviceLogFile\":\"/current\"}");

        Result r = run();

        assertThat(r.code).isEqualTo(SystemdUnitCommand.REFUSED);
        assertThat(r.err).contains("serviceLogFile /current must lie in a directory below");
    }

    @Test
    public void a_supervise_file_that_is_not_a_fifo_is_refused() throws Exception {
        Path stage     = Files.createDirectories(root.resolve("opt/app/stage"));
        Path supervise = Files.createDirectories(services.resolve("app/supervise"));
        Files.writeString(supervise.resolve("control"), "");
        mkfifo(supervise.resolve("ok"));
        Files.createDirectories(logs.resolve("app"));
        config("app", "{\"type\":\"JAR\",\"jarFilename\":\"" + stage.resolve("app.jar") + "\",\"serviceDir\":\"" + services.resolve("app")
                + "\",\"serviceLogFile\":\"" + logs.resolve("app/current") + "\"}");

        Result r = run();

        assertThat(r.err).contains(supervise.resolve("control") + " is not a fifo");
    }

    @Test
    public void a_supervise_fifo_with_a_second_name_is_refused() throws Exception {
        Path stage = Files.createDirectories(root.resolve("opt/app/stage"));
        Path other = supervised(services.resolve("db"));
        Path supervise = Files.createDirectories(services.resolve("app/supervise"));
        Files.createLink(supervise.resolve("control"), other.resolve("control")); // controls db
        mkfifo(supervise.resolve("ok"));
        Files.createDirectories(logs.resolve("app"));
        config("app", "{\"type\":\"JAR\",\"jarFilename\":\"" + stage.resolve("app.jar") + "\",\"serviceDir\":\"" + services.resolve("app")
                + "\",\"serviceLogFile\":\"" + logs.resolve("app/current") + "\"}");

        Result r = run();

        assertThat(r.code).isEqualTo(SystemdUnitCommand.REFUSED);
        assertThat(r.err).contains(supervise.resolve("control") + " has more than one name");
    }

    @Test
    public void the_start_check_of_the_agent_refuses_first() throws Exception {
        Result r = run(c -> List.of("site.json: \"dir\" must lie inside one of COMMAND_DIRS"), "--slice", "pne.slice");

        assertThat(r.code).isEqualTo(SystemdUnitCommand.REFUSED);
        assertThat(r.out).isEmpty();
        assertThat(r.err).contains("must lie inside one of COMMAND_DIRS");
    }

    @Test
    public void more_paths_than_the_launcher_takes_are_refused() throws Exception {
        for (int i = 0; i < SandboxPlanner.MAX_RULES; i++) {
            Path dir = Files.createDirectories(www.resolve("a" + i));
            config("a" + i, "{\"type\":\"SAVE_ARTIFACT\",\"dir\":\"" + dir + "\",\"extension\":\"bin\"}");
        }

        Result r = run();

        assertThat(r.code).isEqualTo(SystemdUnitCommand.REFUSED);
        assertThat(r.err).contains("the launcher takes at most 256");
    }

    @Test
    public void another_services_path_with_unsafe_characters_never_reaches_the_script() throws Exception {
        Path site = Files.createDirectories(www.resolve("site"));
        config("site", "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"" + site + "\"}");
        Path evil = Files.createDirectories(root.resolve("srv/evil'; id; #"));
        Files.createDirectories(definitions.resolve("db"));
        Files.createSymbolicLink(definitions.resolve("db/env"), evil);

        Result r = run("--prepare");

        assertThat(r.code).isEqualTo(SystemdUnitCommand.REFUSED);
        assertThat(r.out).isEmpty();
        assertThat(r.err).contains("has characters the unit and the script cannot take");
    }

    @Test
    public void an_env_of_another_service_outside_the_service_roots_gets_its_own_rd() throws Exception {
        Path site = Files.createDirectories(www.resolve("site"));
        config("site", "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"" + site + "\"}");
        Path env = Files.createDirectories(root.resolve("opt/db/env"));
        Files.createDirectories(definitions.resolve("db"));
        Files.createSymbolicLink(definitions.resolve("db/env"), env);

        Result r = run();

        assertThat(r.err).isEmpty();
        assertThat(r.out).contains("  --rd " + env + " \\\n");
    }

    // ── --check ──────────────────────────────────────────────────────────

    @Test
    public void check_regenerates_with_the_recorded_options_and_settings() throws Exception {
        Path site = Files.createDirectories(www.resolve("site"));
        config("site", "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"" + site + "\"}");
        Path installed = root.resolve("paths.conf");
        Files.writeString(installed, run("--slice", "pne.slice", "--listen", "127.0.0.1:9051").out);

        assertThat(run("--check", installed.toString()).code).isZero();   // the options come from the file

        Path other = Files.createDirectories(www.resolve("other"));
        config("other", "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"" + other + "\"}");
        Result stale = run("--check", installed.toString());
        assertThat(stale.code).isEqualTo(SystemdUnitCommand.DIFFERENT);
        assertThat(stale.out).contains("+   --rw " + other + " \\");

        assertThat(run("--check", root.resolve("none.conf").toString()).code).isEqualTo(SystemdUnitCommand.MISSING);
        Files.writeString(root.resolve("foreign.conf"), "[Service]\nExecStart=/bin/true\n");
        assertThat(run("--check", root.resolve("foreign.conf").toString()).code).isEqualTo(SystemdUnitCommand.DIFFERENT);
    }

    @Test
    public void command_dirs_are_written_without_spaces_and_read_back_alike() {
        assertThat(UnitSettings.canonical("/opt, /srv  /data")).isEqualTo("/opt,/srv,/data");
        UnitSettings settings = new UnitSettings("/opt,/srv", "/service", "/etc/service.d", "/var/log", "10m", "127.0.0.1:8051", "pne.slice");
        StringBuilder dropIn = new StringBuilder("# version\n").append(settings.optionsLine()).append('\n');
        settings.environment().forEach((k, v) -> dropIn.append("Environment=").append(k).append('=').append(v).append('\n'));

        assertThat(UnitSettings.parse(dropIn.toString())).isEqualTo(settings);
    }

    @Test
    public void settings_that_a_shell_could_reinterpret_are_refused() throws Exception {
        Path site = Files.createDirectories(www.resolve("site"));
        config("site", "{\"type\":\"ZIP_ARCHIVE\",\"dir\":\"" + site + "\"}");

        Result r = run("--command-dirs", root.resolve("srv") + ",/opt/x';id;'/x");

        assertThat(r.code).isEqualTo(SystemdUnitCommand.REFUSED);
        assertThat(r.out).isEmpty();
        assertThat(r.err).contains("COMMAND_DIRS", "has characters the unit and the script cannot take");
    }

    @Test
    public void two_wars_in_one_directory_need_both_exploded_trees() throws Exception {
        Path webapps = Files.createDirectories(root.resolve("opt/tomcat/webapps"));
        for (String war : List.of("a", "b")) {
            supervised(services.resolve(war));
            Files.createDirectories(logs.resolve(war));
            config(war, "{\"type\":\"WAR\",\"warFilename\":\"" + webapps.resolve(war + ".war") + "\",\"serviceDir\":\""
                    + services.resolve(war) + "\",\"serviceLogFile\":\"" + logs.resolve(war + "/current") + "\"}");
        }

        SandboxPlan plan = plan();

        assertThat(plan.problems).isEmpty();
        assertThat(plan.trees.get(webapps)).containsExactly("a", "b");
    }

    @Test
    public void bad_options_are_usage_errors() throws Exception {
        assertThat(run("--listen", "localhost:80").code).isEqualTo(SystemdUnitCommand.USAGE);
        assertThat(run("--slice", "pne").code).isEqualTo(SystemdUnitCommand.USAGE);
        assertThat(run("--prepare", "--check").code).isEqualTo(SystemdUnitCommand.USAGE);
        assertThat(run("--apply").code).isEqualTo(SystemdUnitCommand.USAGE);
        assertThat(run("--unknown").code).isEqualTo(SystemdUnitCommand.USAGE);
    }

    // ── host script ──────────────────────────────────────────────────────

    // ── helpers ──────────────────────────────────────────────────────────

    private record Result(int code, String out, String err) {
    }

    private Result run(String... aOptions) throws Exception {
        return run(c -> List.of(), aOptions);
    }

    private Result run(java.util.function.Function<IStartupConfig, List<String>> aValidation, String... aOptions) {
        String[] args = new String[aOptions.length + 1];
        args[0] = SystemdUnitCommand.NAME;
        System.arraycopy(aOptions, 0, args, 1, aOptions.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SystemdUnitCommand.run(args, startupConfig(), config, "test", aValidation,
                c -> new CommandPathPolicy(CommandPathPolicy.parseCommandDirs(c.getCommandDirs()), List.of(home, config),
                        List.of(services, definitions)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private SandboxPlan plan() {
        IStartupConfig config = startupConfig();
        UnitSettings settings = UnitSettings.of(config, null, null, null);
        return new SandboxPlanner(this.config, home, home.resolve("dc-agent"), settings,
                new CommandPathPolicy(CommandPathPolicy.parseCommandDirs(settings.commandDirs()), List.of(home, this.config),
                        List.of(services, definitions))).plan();
    }

    private IStartupConfig startupConfig() {
        return (IStartupConfig) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{IStartupConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getJettyPort"             -> 8051;
                    case "getCommandDirs"           -> root.resolve("opt") + "," + root.resolve("srv");
                    case "getServicesDir"           -> new File(services.toString());
                    case "getServicesDefinitionDir" -> new File(definitions.toString());
                    case "getServicesLogDir"        -> new File(logs.toString());
                    case "getJettyIdleTimeout"      -> "10m";
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
    }

    private void config(String aName, String aJson) throws Exception {
        Files.writeString(config.resolve(aName + ".json"), aJson);
    }

    private static Path supervised(Path aServiceDir) throws Exception {
        Path supervise = Files.createDirectories(aServiceDir.resolve("supervise"));
        mkfifo(supervise.resolve("control"));
        mkfifo(supervise.resolve("ok"));
        Files.write(supervise.resolve("status"), new byte[18]);
        return supervise;
    }

    private static void mkfifo(Path aPath) throws Exception {
        if (new ProcessBuilder("mkfifo", aPath.toString()).inheritIO().start().waitFor() != 0) {
            throw new IllegalStateException("mkfifo " + aPath);
        }
    }
}
