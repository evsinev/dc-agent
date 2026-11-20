package com.payneteasy.dcagent.core.config.validation;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The layout of a host, built in a temporary directory: {@code <tmp>/opt} and {@code <tmp>/srv} are
 * the COMMAND_DIRS, {@code <tmp>/opt/dc-agent} the agent's home, {@code <tmp>/service} the services.
 */
public class CommandPathPolicyTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private Path root;
    private Path opt;
    private Path srv;
    private Path home;
    private Path config;
    private Path services;

    @Before
    public void setUp() throws Exception {
        root     = folder.getRoot().toPath().toRealPath();
        opt      = Files.createDirectories(root.resolve("opt"));
        srv      = Files.createDirectories(root.resolve("srv"));
        home     = Files.createDirectories(opt.resolve("dc-agent"));
        config   = Files.createDirectories(home.resolve("config"));
        services = Files.createDirectories(root.resolve("service"));
    }

    private CommandPathPolicy policy(Path... aAgentPaths) {
        List<Path> agent = aAgentPaths.length > 0 ? List.of(aAgentPaths) : List.of(home, config);
        return new CommandPathPolicy(List.of(opt, srv), agent, List.of(services));
    }

    private List<String> check(Path aDir) {
        return policy().checkDirectory("dir", aDir.toString());
    }

    @Test
    public void inside_a_root_is_allowed() {
        assertThat(check(opt.resolve("site"))).isEmpty();
        assertThat(check(srv.resolve("www/x"))).isEmpty();
    }

    @Test
    public void outside_the_roots_and_a_root_itself_are_refused() {
        for (String outside : new String[]{"/", "/root", "/etc/cron.d", "/var/spool/cron"}) {
            assertThat(policy().checkDirectory("dir", outside)).singleElement().asString().contains("must lie inside one of COMMAND_DIRS");
        }
        assertThat(check(opt)).singleElement().asString().contains("must lie inside");
    }

    @Test
    public void relative_and_dotted_paths_are_refused() {
        assertThat(policy().checkDirectory("dir", "opt/site")).singleElement().asString().contains("absolute path without");
        // checked as /opt/dc-agent would be used, /opt/x/../dc-agent: refused before resolving
        assertThat(policy().checkDirectory("dir", opt + "/x/../dc-agent")).singleElement().asString().contains("without . or ..");
        assertThat(policy().checkDirectory("dir", opt + "/./site")).singleElement().asString().contains("without . or ..");
        assertThat(policy().checkDirectory("dir", "")).containsExactly("\"dir\" is required");
    }

    @Test
    public void a_link_out_of_the_roots_is_refused() throws Exception {
        Path link = Files.createSymbolicLink(opt.resolve("x"), Path.of("/etc"));

        assertThat(check(link)).singleElement().asString().contains("must lie inside").contains("resolves to /").endsWith("etc)");
    }

    @Test
    public void the_agent_home_its_entries_and_its_ancestors_are_refused() {
        assertThat(check(home)).singleElement().asString().contains("overlaps");
        assertThat(check(home.resolve("config"))).singleElement().asString().contains("overlaps");
        assertThat(check(home.resolve("logs"))).singleElement().asString().contains("overlaps");
        // twin
        assertThat(check(opt.resolve("dc-agent2"))).isEmpty();
    }

    @Test
    public void a_config_dir_behind_a_chain_of_links_is_protected_at_every_link() throws Exception {
        // <home>/config2 -> <srv>/relay/config -> <srv>/actual-config
        Path actual = Files.createDirectories(srv.resolve("actual-config"));
        Files.createDirectories(srv.resolve("relay"));
        Files.createSymbolicLink(srv.resolve("relay/config"), actual);
        Path link = Files.createSymbolicLink(home.resolve("config2"), srv.resolve("relay/config"));

        CommandPathPolicy policy = policy(home, link);
        assertThat(policy.checkDirectory("dir", actual.toString())).singleElement().asString().contains("overlaps");
        assertThat(policy.checkDirectory("dir", srv.resolve("relay").toString())).singleElement().asString().contains("overlaps");
        assertThat(policy.checkDirectory("dir", srv.resolve("other").toString())).isEmpty();
    }

    @Test
    public void the_agent_jar_as_started_is_protected_at_every_link() throws Exception {
        // <home>/dc-agent.jar -> <srv>/relay/current.jar -> <srv>/releases/agent.jar
        Path release = Files.createDirectories(srv.resolve("releases")).resolve("agent.jar");
        Files.writeString(release, "jar");
        Files.createDirectories(srv.resolve("relay"));
        Files.createSymbolicLink(srv.resolve("relay/current.jar"), release);
        Files.createSymbolicLink(home.resolve("dc-agent.jar"), srv.resolve("relay/current.jar"));

        assertThat(check(srv.resolve("relay"))).singleElement().asString().contains("overlaps");
        assertThat(check(srv.resolve("releases"))).singleElement().asString().contains("overlaps");
    }

    @Test
    public void a_service_behind_a_link_is_protected() throws Exception {
        // <service>/app -> <srv>/app/supervise
        Path real = Files.createDirectories(srv.resolve("app/supervise"));
        Files.createSymbolicLink(services.resolve("app"), real);

        assertThat(check(real)).singleElement().asString().contains("overlaps");
        assertThat(check(srv.resolve("app"))).singleElement().asString().contains("overlaps");
        assertThat(check(srv.resolve("app2"))).isEmpty();
    }

    @Test
    public void the_log_run_behind_two_links_is_protected() throws Exception {
        // <service>/app/log -> <srv>/logger ; <srv>/logger/run -> <srv>/scripts/logger/run
        Path app    = Files.createDirectories(services.resolve("app"));
        Path logger = Files.createDirectories(srv.resolve("logger"));
        Path script = Files.createDirectories(srv.resolve("scripts/logger")).resolve("run");
        Files.writeString(script, "#!/bin/sh\n");
        Files.createSymbolicLink(logger.resolve("run"), script);
        Files.createSymbolicLink(app.resolve("log"), logger);

        assertThat(check(srv.resolve("scripts/logger"))).singleElement().asString().contains("overlaps");
        assertThat(check(logger)).singleElement().asString().contains("overlaps");
        assertThat(check(srv.resolve("scripts/other"))).isEmpty();
    }

    @Test
    public void an_env_value_behind_links_is_protected_at_every_link_even_when_missing() throws Exception {
        // <service>/app/env/LD_PRELOAD -> <srv>/stage/value -> <srv>/env-data/value
        Path env  = Files.createDirectories(services.resolve("app/env"));
        Path data = Files.createDirectories(srv.resolve("env-data"));
        Files.writeString(data.resolve("value"), "/lib/x.so");
        Files.createDirectories(srv.resolve("stage"));
        Files.createSymbolicLink(srv.resolve("stage/value"), data.resolve("value"));
        Files.createSymbolicLink(env.resolve("LD_PRELOAD"), srv.resolve("stage/value"));
        // a dangling one: <service>/app/env/EXTRA -> <srv>/inject/preload (not there yet)
        Files.createSymbolicLink(env.resolve("EXTRA"), srv.resolve("inject/preload"));

        assertThat(check(srv.resolve("stage"))).singleElement().asString().contains("overlaps");
        assertThat(check(data)).singleElement().asString().contains("overlaps");
        assertThat(check(srv.resolve("inject"))).singleElement().asString().contains("overlaps");
        assertThat(check(srv.resolve("free"))).isEmpty();
    }

    @Test
    public void a_file_among_the_services_is_not_a_service_and_does_not_break_the_check() throws Exception {
        Files.writeString(services.resolve("README"), "not a service");

        assertThat(check(opt.resolve("site"))).isEmpty();
    }

    @Test
    public void a_link_loop_is_a_problem_not_a_hang() throws Exception {
        Files.createSymbolicLink(opt.resolve("a"), opt.resolve("b"));
        Files.createSymbolicLink(opt.resolve("b"), opt.resolve("a"));

        assertThat(check(opt.resolve("a/x"))).singleElement().asString().contains("cannot be resolved");
    }

    @Test
    public void service_dir_must_be_a_service_in_a_service_root() {
        assertThat(policy().checkServiceDir(services.resolve("app").toString())).isEmpty();
        assertThat(policy().checkServiceDir(srv.resolve("custom/svc").toString())).singleElement().asString().contains("must be a service directly in");
        assertThat(policy().checkServiceDir(services.resolve("app/log").toString())).hasSize(1);
        assertThat(policy().checkServiceDir(services + "/x/../app")).hasSize(1);
    }

    @Test
    public void command_dirs_accept_commas_and_whitespace() {
        List<Path> expected = List.of(Path.of("/opt"), Path.of("/srv"));

        assertThat(CommandPathPolicy.parseCommandDirs("/opt,/srv")).isEqualTo(expected);
        assertThat(CommandPathPolicy.parseCommandDirs("/opt, /srv")).isEqualTo(expected);
        assertThat(CommandPathPolicy.parseCommandDirs(" /opt /srv ")).isEqualTo(expected);
        assertThat(CommandPathPolicy.parseCommandDirs("/opt,,\t/srv,")).isEqualTo(expected);
    }

    @Test
    public void bad_command_dirs_are_refused() {
        for (String bad : new String[]{"", " , ", "opt", "/", "/opt/../etc", null}) {
            assertThatThrownBy(() -> CommandPathPolicy.parseCommandDirs(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    public void an_empty_or_missing_env_behind_a_link_is_protected() throws Exception {
        // <service>/app/env -> <srv>/inject (empty); <service>/app/log/env -> <srv>/later (missing)
        Path app = Files.createDirectories(services.resolve("app/log"));
        Files.createSymbolicLink(services.resolve("app/env"), Files.createDirectories(srv.resolve("inject")));
        Files.createSymbolicLink(app.resolve("env"), srv.resolve("later"));

        assertThat(check(srv.resolve("inject"))).singleElement().asString().contains("overlaps");
        assertThat(check(srv.resolve("later"))).singleElement().asString().contains("overlaps");
        assertThat(check(srv.resolve("free"))).isEmpty();
    }

    @Test
    public void a_jar_started_outside_the_home_through_a_link_is_protected_at_every_link() throws Exception {
        // java -jar <srv>/relay/current, current -> <srv>/releases/agent.jar; not in the home
        Path release = Files.createDirectories(srv.resolve("releases")).resolve("agent.jar");
        Files.writeString(release, "jar");
        Path current = Files.createSymbolicLink(Files.createDirectories(srv.resolve("relay")).resolve("current"), release);

        CommandPathPolicy policy = policy(home, config, current);
        assertThat(policy.checkDirectory("dir", srv.resolve("relay").toString())).singleElement().asString().contains("overlaps");
        assertThat(policy.checkDirectory("dir", srv.resolve("releases").toString())).singleElement().asString().contains("overlaps");
        // twin: without the started path nothing protects relay
        assertThat(policy(home, config).checkDirectory("dir", srv.resolve("relay").toString())).isEmpty();
    }
}
