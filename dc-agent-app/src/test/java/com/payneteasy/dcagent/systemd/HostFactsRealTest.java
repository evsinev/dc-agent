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
