package com.payneteasy.dcagent.systemd;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The real host facts on Linux, as the build user: attributes without following links, ACLs from getfacl, the walk. */
public class HostFactsRealTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder(new File(System.getProperty("user.dir"), "target"));

    @Test
    public void attributes_the_walk_and_lines_without_acl_tools() throws Exception {
        HostFacts facts = new HostFacts.Real();
        Path base = tmp.getRoot().toPath().toAbsolutePath();
        Path dir  = Files.createDirectories(base.resolve("d/sub"));
        Path file = Files.writeString(dir.resolve("f"), "A=1\nB=2\n");
        Path fifo = base.resolve("fifo");
        assertThat(new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor()).isZero();

        assertThat(facts.attrs(dir)).get().satisfies(a -> {
            assertThat(a.type()).isEqualTo(HostFacts.Type.DIR);
            assertThat(a.writableByGroupOrOthers()).isEqualTo((a.mode() & 0022) != 0);
        });
        assertThat(facts.attrs(fifo)).get().extracting(HostFacts.Attrs::type).isEqualTo(HostFacts.Type.FIFO);
        assertThat(facts.attrs(base.resolve("missing"))).isEmpty();
        assertThat(facts.walk(base.resolve("d"))).containsExactly(file, dir, base.resolve("d"));
        assertThat(facts.walk(base.resolve("missing"))).isNull(); // not walked: unknown
        assertThat(facts.lines(file)).containsExactly("A=1", "B=2");
        assertThat(facts.lines(base.resolve("missing"))).isEmpty();
        assertThat(facts.userExists(System.getProperty("user.name"))).isTrue();
        assertThat(facts.userExists("dc-agent-test-nobody-x")).isFalse();
        assertThat(facts.mountInside(base)).isNull();
        // as the agent: runuser is root's, so as the build user every answer is "cannot"
        if (!"root".equals(System.getProperty("user.name"))) {
            assertThat(facts.agentCan(file, "-r", "-w")).isFalse();
            assertThat(facts.agentObstacle(base.resolve("d"))).isNotNull();
        }
        assertThat(facts.acl(base.resolve("missing"))).containsExactly(HostFacts.Real.UNKNOWN);
    }

    @Test
    public void attributes_acls_links_and_the_walk_come_from_the_file_system() throws Exception {
        Assume.assumeTrue("Linux with getfacl/setfacl", new File("/usr/bin/getfacl").canExecute() && new File("/usr/bin/setfacl").canExecute());
        HostFacts facts = new HostFacts.Real();
        Path dir  = Files.createDirectories(tmp.getRoot().toPath().resolve("d/sub"));
        Path file = Files.writeString(dir.resolve("f"), "x");
        Files.createLink(tmp.getRoot().toPath().resolve("hard"), file);
        Files.createSymbolicLink(tmp.getRoot().toPath().resolve("link"), file);

        assertThat(facts.attrs(file)).get().satisfies(a -> {
            assertThat(a.type()).isEqualTo(HostFacts.Type.FILE);
            assertThat(a.links()).isEqualTo(2);
            assertThat(a.owner()).isEqualTo(System.getProperty("user.name"));
        });
        assertThat(facts.attrs(tmp.getRoot().toPath().resolve("link"))).get().extracting(HostFacts.Attrs::type).isEqualTo(HostFacts.Type.LINK);
        assertThat(facts.acl(file)).isEmpty();

        new ProcessBuilder("setfacl", "-m", "u:" + System.getProperty("user.name") + ":r--", file.toString()).inheritIO().start().waitFor();
        assertThat(facts.acl(file)).anyMatch(e -> e.startsWith("user:" + System.getProperty("user.name") + ":r"));
        assertThat(facts.aclsInTree(tmp.getRoot().toPath().resolve("d"))).isNotEmpty();

        // post-order: the file before its directory, the directory before its parent
        assertThat(facts.walk(tmp.getRoot().toPath().resolve("d"))).containsExactly(file, dir, tmp.getRoot().toPath().resolve("d"));
    }
}
