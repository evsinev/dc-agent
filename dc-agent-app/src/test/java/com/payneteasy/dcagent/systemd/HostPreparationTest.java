package com.payneteasy.dcagent.systemd;

import com.payneteasy.dcagent.systemd.HostFacts.Attrs;
import com.payneteasy.dcagent.systemd.HostFacts.Type;
import com.payneteasy.dcagent.systemd.SandboxPlan.Kind;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decisions of the host preparation on invented hosts: what it changes by itself (only where root
 * alone controls the path) and what it hands to a human — root-owned trees, foreign owners, ACLs, hard
 * links, mounts, the order of changes. Nothing touches the real file system.
 */
public class HostPreparationTest {

    private Fake        facts;
    private SandboxPlan plan;
    private final UnitSettings settings = new UnitSettings("/opt,/srv", "/service", "/etc/service.d", "/var/log", "10m", "127.0.0.1:8051", null);

    @Before
    public void setUp() {
        facts = new Fake();
        plan  = new SandboxPlan();
        // a prepared base: the agent home, the config, the roots, the env file, sysctl
        for (String dir : List.of("/", "/opt", "/srv", "/etc", "/etc/service.d", "/service", "/etc/dc-agent", "/etc/sysctl.d", "/var", "/var/log")) {
            facts.dir(dir, "root", "root", 0755);
        }
        facts.dir("/opt/dc-agent", "root", "root", 0755);
        facts.dir("/opt/dc-agent/config", "root", "dc-agent", 0750);
        facts.file("/opt/dc-agent/config/.dc-agent.lock", "dc-agent", "dc-agent", 0600, 1);
        facts.file("/etc/dc-agent/env", "root", "root", 0600, 1);
        facts.lines.put(Path.of("/etc/dc-agent/env"), List.of("APP_STATUS_TOKEN=x"));
        facts.file("/etc/sysctl.d/90-dc-agent.conf", "root", "root", 0644, 1);
        facts.lines.put(Path.of("/proc/sys/net/ipv4/tcp_fastopen"), List.of("0"));
        facts.users.add("dc-agent");
    }

    private List<HostPreparation.Step> decide() {
        return new HostPreparation(plan, settings, Path.of("/opt/dc-agent/config"), Path.of("/opt/dc-agent"), facts).decide();
    }

    private static List<String> changes(List<HostPreparation.Step> aSteps) {
        return aSteps.stream().filter(s -> !s.handoff()).map(HostPreparation.Step::subject).toList();
    }

    private static List<String> handoffs(List<HostPreparation.Step> aSteps) {
        return aSteps.stream().filter(HostPreparation.Step::handoff).map(HostPreparation.Step::subject).toList();
    }

    @Test
    public void a_prepared_host_needs_nothing() {
        assertThat(decide()).isEmpty();
    }

    @Test
    public void a_tree_only_root_controls_is_granted_bottom_up_with_default_acls_first() throws Exception {
        tree("/srv/www/site", null);
        facts.dir("/srv/www", "root", "root", 0755);
        facts.dir("/srv/www/site", "root", "root", 0755);
        facts.dir("/srv/www/site/css", "root", "root", 0755);
        facts.file("/srv/www/site/css/a.css", "root", "root", 0644, 1);
        facts.obstacles.put(Path.of("/srv/www/site"), "/srv/www/site");

        List<HostPreparation.Step> steps = decide();
        assertThat(changes(steps)).containsExactly("/srv/www/site");
        Recorder actions = new Recorder();
        steps.get(0).action().run(actions);

        assertThat(actions.calls).containsExactly(
                "setfacl d:u:dc-agent:rwX [/srv/www/site/css, /srv/www/site]",
                "setfacl u:dc-agent:rwX [/srv/www/site/css/a.css, /srv/www/site/css, /srv/www/site]");
    }

    @Test
    public void a_tree_someone_else_can_change_is_handed_off() {
        tree("/srv/www/skin", null);
        facts.dir("/srv/www", "root", "root", 0755);
        facts.dir("/srv/www/skin", "paynet", "paynet", 0755);
        facts.obstacles.put(Path.of("/srv/www/skin"), "/srv/www/skin");

        assertThat(handoffs(decide())).containsExactly("/srv/www/skin");
    }

    @Test
    public void a_root_tree_with_a_hard_link_an_acl_or_a_mount_inside_is_handed_off() {
        facts.dir("/srv/www", "root", "root", 0755);
        for (String name : List.of("linked", "acl", "mounted", "writable")) {
            tree("/srv/www/" + name, null);
            facts.dir("/srv/www/" + name, "root", "root", 0755);
            facts.obstacles.put(Path.of("/srv/www/" + name), "x");
        }
        facts.file("/srv/www/linked/f", "root", "root", 0600, 2);
        facts.treeAcls.put(Path.of("/srv/www/acl"), List.of("user:deploy:r--"));
        facts.mounts.put(Path.of("/srv/www/mounted"), "/srv/www/mounted/db");
        facts.dir("/srv/www/writable/sub", "root", "root", 0775);

        assertThat(handoffs(decide())).containsExactlyInAnyOrder("/srv/www/linked", "/srv/www/acl", "/srv/www/mounted", "/srv/www/writable");
    }

    @Test
    public void node_subtrees_change_before_their_directory() {
        tree("/opt/app", List.of("app", "target"));
        facts.dir("/opt/app", "root", "root", 0755);
        facts.dir("/opt/app/target", "root", "root", 0755);
        facts.obstacles.put(Path.of("/opt/app/target"), "/opt/app/target");
        facts.agent.remove("/opt/app -w"); // the agent cannot write in /opt/app yet

        assertThat(changes(decide())).containsExactly("/opt/app/target", "/opt/app");
    }

    @Test
    public void supervise_root_alone_controls_gets_its_fifos_and_status_together() throws Exception {
        service("/etc/service.d/app", "root", null);
        facts.agent.remove(Path.of("/etc/service.d/app/supervise/status") + " -r");

        List<HostPreparation.Step> steps = decide();
        assertThat(changes(steps)).containsExactly("/etc/service.d/app/supervise");
        assertThat(handoffs(steps)).isEmpty();
        Recorder actions = new Recorder();
        steps.get(0).action().run(actions);
        assertThat(actions.calls).contains("chmod /etc/service.d/app/supervise/control rw--w----",
                "setfacl u:dc-agent:r-- [/etc/service.d/app/supervise/status]");
    }

    @Test
    public void supervise_of_a_service_owned_by_its_user_or_with_an_acl_is_handed_off() {
        service("/etc/service.d/paynet", "paynet", null);
        service("/etc/service.d/acl", "root", "user:deploy:-w-");

        assertThat(handoffs(decide())).containsExactlyInAnyOrder("/etc/service.d/paynet/supervise", "/etc/service.d/acl/supervise");
    }

    @Test
    public void the_way_to_a_supervise_directory_is_not_changed_separately() {
        service("/etc/service.d/app", "root", null);
        plan.reached.add(Path.of("/etc/service.d/app/supervise/control"));

        assertThat(changes(decide())).containsExactly("/etc/service.d/app/supervise"); // no extra --x on it
    }

    @Test
    public void all_logs_of_a_shared_directory_are_made_readable_in_one_change() throws Exception {
        facts.dir("/var/log/apps", "root", "root", 0700);
        facts.file("/var/log/apps/a.log", "root", "root", 0600, 1);
        facts.file("/var/log/apps/b.log", "root", "root", 0600, 1);
        for (String name : List.of("a", "b")) {
            plan.services.add(new SandboxPlan.Service(name + ".json", Path.of("/service/" + name), Path.of("/x/" + name),
                    Path.of("/var/log/apps"), Path.of("/var/log/apps/" + name + ".log")));
        }
        facts.agent.removeIf(k -> k.startsWith("/var/log/apps"));
        facts.fifosOk("/x/a");
        facts.fifosOk("/x/b");

        List<HostPreparation.Step> steps = decide();
        assertThat(changes(steps)).containsExactly("/var/log/apps");
        Recorder actions = new Recorder();
        steps.get(0).action().run(actions);
        assertThat(actions.calls).contains("setfacl u:dc-agent:r-- [/var/log/apps/a.log, /var/log/apps/b.log]");
    }

    @Test
    public void a_hard_linked_config_lock_and_an_overriding_env_are_handed_off() {
        facts.file("/opt/dc-agent/config/.dc-agent.lock", "dc-agent", "dc-agent", 0600, 2);
        facts.lines.put(Path.of("/etc/dc-agent/env"), List.of("APP_STATUS_TOKEN=x", "WEB_SERVER_INHERITED=false"));

        assertThat(handoffs(decide())).containsExactly("/opt/dc-agent/config", "/etc/dc-agent/env");
    }

    @Test
    public void a_foreign_sysctl_file_is_neither_read_nor_applied_but_the_one_setting_is() throws Exception {
        facts.file("/etc/sysctl.d/90-dc-agent.conf", "deploy", "deploy", 0644, 1);
        facts.lines.put(Path.of("/proc/sys/net/ipv4/tcp_fastopen"), List.of("1"));

        List<HostPreparation.Step> steps = decide();
        assertThat(handoffs(steps)).containsExactly("/etc/sysctl.d/90-dc-agent.conf");
        Recorder actions = new Recorder();
        for (HostPreparation.Step step : steps) {
            if (!step.handoff()) {
                step.action().run(actions);
            }
        }
        assertThat(actions.calls).containsExactly("sysctl net.ipv4.tcp_fastopen=0");
    }

    @Test
    public void root_only_refuses_any_writer_but_root_up_to_the_root() {
        facts.dir("/srv/a", "root", "root", 0755);
        facts.dir("/srv/a/b", "root", "root", 0755);
        HostPreparation preparation = new HostPreparation(plan, settings, Path.of("/opt/dc-agent/config"), Path.of("/opt/dc-agent"), facts);
        assertThat(preparation.rootOnly(Path.of("/srv/a/b"))).isTrue();

        facts.acls.put(Path.of("/srv/a"), List.of("user:dc-agent:rwx")); // the agent's own write counts too
        assertThat(preparation.rootOnly(Path.of("/srv/a/b"))).isFalse();
        facts.acls.remove(Path.of("/srv/a"));
        facts.dir("/srv", "root", "root", 0775);
        assertThat(preparation.rootOnly(Path.of("/srv/a/b"))).isFalse();
    }

    @Test
    public void a_tree_inside_a_whole_tree_is_granted_with_it_and_deeper_trees_go_first() {
        facts.dir("/srv/data", "root", "root", 0755);
        facts.dir("/srv/data/sub", "root", "root", 0755);
        facts.dir("/srv/data/sub/root", "root", "root", 0755);
        tree("/srv/data", null);
        tree("/srv/data/sub/root", null);
        for (String dir : List.of("/srv/data", "/srv/data/sub/root")) {
            facts.obstacles.put(Path.of(dir), dir);
        }
        assertThat(changes(decide())).containsExactly("/srv/data");

        plan = new SandboxPlan();
        facts.dir("/opt/app", "root", "root", 0755);
        facts.dir("/opt/app/target", "root", "root", 0755);
        facts.dir("/opt/app/static", "root", "root", 0755);
        facts.agent.remove("/opt/app -w");
        tree("/opt/app", List.of("target"));
        tree("/opt/app/static", null);
        for (String dir : List.of("/opt/app/target", "/opt/app/static")) {
            facts.obstacles.put(Path.of(dir), dir);
        }
        assertThat(changes(decide())).containsExactly("/opt/app/static", "/opt/app/target", "/opt/app");
    }

    @Test
    public void apply_asks_again_right_before_each_change_and_stops_when_the_host_changed() {
        tree("/srv/www/site", null);
        facts.dir("/srv/www", "root", "root", 0755);
        facts.dir("/srv/www/site", "root", "root", 0755);
        facts.obstacles.put(Path.of("/srv/www/site"), "/srv/www/site");
        List<HostPreparation.Step> steps = decide();
        assertThat(changes(steps)).containsExactly("/srv/www/site");

        facts.dir("/srv/www/site/evil", "paynet", "paynet", 0755); // appeared after the decision
        Recorder actions = new Recorder();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertThat(HostPreparation.apply(steps, actions, new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8))).isFalse();
        assertThat(actions.calls).isEmpty();
        assertThat(out.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("SKIPPED  /srv/www/site");
    }

    @Test
    public void a_tree_that_cannot_be_walked_completely_is_handed_off() {
        tree("/srv/www/site", null);
        facts.dir("/srv/www", "root", "root", 0755);
        facts.dir("/srv/www/site", "root", "root", 0755);
        facts.obstacles.put(Path.of("/srv/www/site"), "/srv/www/site");
        facts.unwalkable.add(Path.of("/srv/www/site"));

        assertThat(handoffs(decide())).containsExactly("/srv/www/site");
    }

    @Test
    public void an_acl_getfacl_cannot_read_counts_as_foreign() {
        facts.dir("/srv/a", "root", "root", 0755);
        HostPreparation preparation = new HostPreparation(plan, settings, Path.of("/opt/dc-agent/config"), Path.of("/opt/dc-agent"), facts);
        facts.acls.put(Path.of("/srv"), List.of(HostFacts.Real.UNKNOWN));
        assertThat(preparation.rootOnly(Path.of("/srv/a"))).isFalse();
    }

    @Test
    public void service_roots_and_env_dirs_inside_them_are_made_listable() {
        facts.agent.remove("/service -r");
        facts.dir("/etc/service.d/db", "root", "root", 0755);
        facts.dir("/etc/service.d/db/env", "root", "root", 0700);
        facts.agent.remove("/etc/service.d/db/env -r");
        facts.agent.remove("/etc/service.d/db/env -x");
        plan.listed.add(Path.of("/service"));
        plan.listed.add(Path.of("/etc/service.d/db/env"));

        assertThat(changes(decide())).containsExactly("/service", "/etc/service.d/db/env");
    }

    @Test
    public void a_supervise_fifo_with_a_second_name_is_handed_off() {
        service("/etc/service.d/app", "root", null);
        facts.attrs.put(Path.of("/etc/service.d/app/supervise/control"), new Attrs(Type.FIFO, "root", "root", 0600, 2, 1));

        List<HostPreparation.Step> steps = decide();
        assertThat(changes(steps)).isEmpty();
        assertThat(handoffs(steps)).containsExactly("/etc/service.d/app/supervise/control");
    }

    @Test
    public void the_status_is_checked_again_right_before_it_is_made_readable() {
        service("/etc/service.d/app", "root", null);
        facts.agent.remove(Path.of("/etc/service.d/app/supervise/status") + " -r");
        List<HostPreparation.Step> steps = decide();
        assertThat(changes(steps)).containsExactly("/etc/service.d/app/supervise");

        facts.file("/etc/service.d/app/supervise/status", "root", "root", 0000, 2); // now another name of a foreign file
        Recorder actions = new Recorder();
        assertThat(HostPreparation.apply(steps, actions, new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true,
                java.nio.charset.StandardCharsets.UTF_8))).isFalse();
        assertThat(actions.calls).isEmpty();
    }

    @Test
    public void a_tree_inside_a_node_subtree_is_granted_with_the_subtree() {
        facts.dir("/opt/app", "root", "root", 0755);
        facts.dir("/opt/app/target", "root", "root", 0755);
        facts.dir("/opt/app/target/assets", "root", "root", 0755);
        facts.agent.remove("/opt/app -w");
        tree("/opt/app", List.of("app", "target"));
        tree("/opt/app/target/assets", null);
        for (String dir : List.of("/opt/app/target", "/opt/app/target/assets")) {
            facts.obstacles.put(Path.of(dir), dir);
        }
        assertThat(changes(decide())).containsExactly("/opt/app/target", "/opt/app");
    }

    @Test
    public void a_log_directory_that_got_an_acl_is_not_changed() {
        facts.dir("/var/log/app", "root", "root", 0700);
        facts.agent.remove("/var/log/app -r");
        facts.file("/var/log/app/current", "root", "root", 0644, 1);
        facts.fifosOk("/service/app/supervise"); // supervise prepared already
        plan.services.add(new SandboxPlan.Service("app.json", Path.of("/service/app"), Path.of("/service/app/supervise"),
                Path.of("/var/log/app"), Path.of("/var/log/app/current")));
        List<HostPreparation.Step> steps = decide().stream().filter(s -> s.subject().equals("/var/log/app")).toList();
        assertThat(changes(steps)).containsExactly("/var/log/app");

        facts.acls.put(Path.of("/var/log/app"), List.of("user:deploy:r-x", "mask::---")); // after the decision
        Recorder actions = new Recorder();
        assertThat(HostPreparation.apply(steps, actions, new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true,
                java.nio.charset.StandardCharsets.UTF_8))).isFalse();
        assertThat(actions.calls).isEmpty();
    }

    @Test
    public void a_listed_directory_inside_a_granted_tree_is_left_to_the_tree() {
        facts.dir("/srv/data", "root", "root", 0755);
        facts.dir("/srv/data/releases", "root", "root", 0700);
        facts.agent.remove("/srv/data/releases -r");
        facts.obstacles.put(Path.of("/srv/data"), "/srv/data/releases");
        tree("/srv/data", null);
        plan.listed.add(Path.of("/srv/data/releases"));

        assertThat(changes(decide())).containsExactly("/srv/data");
    }

    @Test
    public void one_service_in_two_configs_is_changed_once() {
        service("/etc/service.d/app", "root", null);
        plan.services.add(plan.services.get(0));

        assertThat(changes(decide())).containsExactly("/etc/service.d/app/supervise");
    }

    @Test
    public void a_subtree_name_that_leaves_the_directory_is_handed_off() {
        facts.dir("/opt/app", "root", "root", 0755);
        facts.obstacles.put(Path.of("/opt"), "/opt/dc-agent/config");
        tree("/opt/app", List.of(".."));

        List<HostPreparation.Step> steps = decide();
        assertThat(changes(steps)).isEmpty();
        assertThat(handoffs(steps)).containsExactly("/opt/app");
    }

    @Test
    public void a_sticky_directory_in_a_root_tree_is_handed_off() {
        tree("/srv/site", null);
        facts.dir("/srv/site", "root", "root", 01755);
        facts.file("/srv/site/index.html", "root", "root", 0644, 1);
        facts.obstacles.put(Path.of("/srv/site"), "/srv/site/index.html");

        assertThat(handoffs(decide())).containsExactly("/srv/site");
    }

    @Test
    public void a_missing_directory_inside_a_granted_tree_is_still_created() {
        facts.dir("/srv/data", "root", "root", 0755);
        tree("/srv/data", null);
        tree("/srv/data/stage", List.of());

        assertThat(changes(decide())).containsExactly("/srv/data/stage");
    }

    @Test
    public void a_missing_directory_inside_a_tree_granted_in_the_same_run_is_root_s_until_the_grant() {
        facts.dir("/srv/data", "root", "root", 0755);
        facts.agent.remove("/srv/data -w");
        facts.obstacles.put(Path.of("/srv/data"), "/srv/data");
        tree("/srv/data", null);
        tree("/srv/data/stage", List.of());
        List<HostPreparation.Step> steps = decide();
        assertThat(changes(steps)).containsExactly("/srv/data/stage", "/srv/data");

        Applying actions = new Applying(facts);
        assertThat(HostPreparation.apply(steps, actions, new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true,
                java.nio.charset.StandardCharsets.UTF_8))).isTrue();
        assertThat(actions.calls).containsExactly("mkdir /srv/data/stage root",
                "setfacl d:u:dc-agent:rwX [/srv/data/stage, /srv/data]", "setfacl u:dc-agent:rwX [/srv/data/stage, /srv/data]");
    }

    @Test
    public void hand_off_commands_quote_every_path_as_one_shell_word() {
        assertThat(HostPreparation.q("/opt/app/x';id;#")).isEqualTo("'/opt/app/x'\\'';id;#'");
        tree("/srv/www/it's", null);
        facts.dir("/srv/www", "root", "root", 0755);
        facts.dir("/srv/www/it's", "paynet", "paynet", 0755);
        facts.obstacles.put(Path.of("/srv/www/it's"), "x");

        assertThat(decide().get(0).commands().get(0)).contains("chown -R dc-agent '/srv/www/it'\\''s'");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void tree(String aDir, List<String> aSubtrees) {
        plan.add(Kind.RW, Path.of(aDir), "x.json", "test");
        plan.trees.put(Path.of(aDir), aSubtrees == null ? null : new ArrayList<>(aSubtrees));
    }

    /** A service whose supervise dir belongs to aOwner; fifos not yet writable by the agent. */
    private void service(String aDir, String aOwner, String aControlAcl) {
        Path s = Path.of(aDir, "supervise");
        facts.dir(aDir, aOwner, aOwner, 0755);
        facts.dir(s.toString(), aOwner, aOwner, 0700);
        facts.attrs.put(s.resolve("control"), new Attrs(Type.FIFO, aOwner, aOwner, 0600, 1, 1));
        facts.attrs.put(s.resolve("ok"), new Attrs(Type.FIFO, aOwner, aOwner, 0600, 1, 1));
        facts.file(s.resolve("status").toString(), "root", "root", 0644, 1);
        facts.agent.add(s.resolve("status") + " -r");
        if (aControlAcl != null) {
            facts.acls.put(s.resolve("control"), List.of(aControlAcl));
        }
        plan.services.add(new SandboxPlan.Service(aDir + ".json", Path.of(aDir), s, null, null));
    }

    /** Records the changes instead of making them. */
    private static class Recorder implements HostActions {
        final List<String> calls = new ArrayList<>();

        @Override public void setfacl(String aSpec, List<Path> aPaths) { calls.add("setfacl " + aSpec + " " + aPaths); }
        @Override public void chown(Path aPath, String aOwner, String aGroup) { calls.add("chown " + aPath + " " + aOwner + ":" + aGroup); }
        @Override public void chmod(Path aPath, String aMode) { calls.add("chmod " + aPath + " " + aMode); }
        @Override public void mkdir(Path aPath, String aOwner, String aMode) { calls.add("mkdir " + aPath); }
        @Override public void create(Path aPath, String aOwner, String aMode, String aContent) { calls.add("create " + aPath); }
        @Override public void useradd(String aUser) { calls.add("useradd " + aUser); }
        @Override public void sysctl(String aAssignment) { calls.add("sysctl " + aAssignment); }
    }

    /** Makes the changes on the invented host, so a later step's precondition sees them. */
    private static final class Applying extends Recorder {
        private final Fake host;

        Applying(Fake aHost) {
            host = aHost;
        }

        @Override public void mkdir(Path aPath, String aOwner, String aMode) {
            calls.add("mkdir " + aPath + " " + (aOwner == null ? "root" : aOwner));
            host.dir(aPath.toString(), aOwner == null ? "root" : aOwner, aOwner == null ? "root" : aOwner, 0755);
        }

        @Override public void setfacl(String aSpec, List<Path> aPaths) {
            super.setfacl(aSpec, aPaths);
            for (Path path : aPaths) {
                List<String> acl = new ArrayList<>(host.acl(path));
                acl.add(aSpec);
                host.acls.put(path, acl);
            }
        }
    }

    /** An invented host: attributes, ACLs, what the agent can do; walk() derives post-order from the paths. */
    private static final class Fake implements HostFacts {
        final Map<Path, Attrs>        attrs     = new HashMap<>();
        final Map<Path, List<String>> acls      = new HashMap<>();
        final Map<Path, List<String>> treeAcls  = new HashMap<>();
        final Map<Path, String>       mounts    = new HashMap<>();
        final Map<Path, String>       obstacles = new HashMap<>();
        final Map<Path, List<String>> lines     = new HashMap<>();
        final Set<String>             agent     = new HashSet<>();
        final Set<String>             users     = new HashSet<>();
        final Set<Path>               unwalkable = new HashSet<>();

        void dir(String aPath, String aOwner, String aGroup, int aMode) {
            attrs.put(Path.of(aPath), new Attrs(Type.DIR, aOwner, aGroup, aMode, 2, 1));
            for (String flag : List.of("-r", "-x", "-w")) {
                agent.add(aPath + " " + flag); // the agent may by default; tests take it away
            }
        }

        void file(String aPath, String aOwner, String aGroup, int aMode, long aLinks) {
            attrs.put(Path.of(aPath), new Attrs(Type.FILE, aOwner, aGroup, aMode, aLinks, 1));
            agent.add(aPath + " -r");
        }

        void fifosOk(String aSupervise) {
            Path s = Path.of(aSupervise);
            attrs.put(s, new Attrs(Type.DIR, "root", "dc-agent", 0710, 2, 1));
            attrs.put(s.resolve("control"), new Attrs(Type.FIFO, "root", "dc-agent", 0620, 1, 1));
            attrs.put(s.resolve("ok"), new Attrs(Type.FIFO, "root", "dc-agent", 0620, 1, 1));
            agent.addAll(List.of(s + " -x", s.resolve("control") + " -w", s.resolve("ok") + " -w"));
        }

        @Override public Optional<Attrs> attrs(Path aPath) { return Optional.ofNullable(attrs.get(aPath)); }
        @Override public List<String> acl(Path aPath) { return acls.getOrDefault(aPath, List.of()); }
        @Override public List<String> aclsInTree(Path aDir) { return treeAcls.getOrDefault(aDir, List.of()); }
        @Override public String mountInside(Path aDir) { return mounts.get(aDir); }
        @Override public String agentObstacle(Path aDir) { return obstacles.get(aDir); }
        @Override public boolean userExists(String aUser) { return users.contains(aUser); }
        @Override public List<String> lines(Path aFile) { return lines.getOrDefault(aFile, List.of()); }

        @Override
        public boolean agentCan(Path aPath, String... aFlags) {
            for (String flag : aFlags) {
                if (!agent.contains(aPath + " " + flag)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public List<Path> walk(Path aDir) {
            if (unwalkable.contains(aDir)) {
                return null;
            }
            return attrs.keySet().stream().filter(p -> p.startsWith(aDir))
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed().thenComparing(Comparator.naturalOrder())).toList();
        }
    }
}
