package com.payneteasy.dcagent.core.modules.docker.preflight;

import com.payneteasy.dcagent.core.config.model.docker.Owner;
import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.modules.docker.resolver.DockerResolver;
import com.payneteasy.dcagent.core.modules.docker.resolver.RecordingFileSystem;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;
import com.sun.security.auth.module.UnixSystem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules of WritePathPreflight (plan, section "Безопасность"), each scenario from the plan reviews.
 * Runs without root: the agent's own uid is trusted, a "foreign" owner is faked by the reader.
 */
public class WritePathPreflightTest {

    private static final int ME = (int) new UnixSystem().getUid();

    private Path base;
    private Path app;
    private Path upload;

    @Before
    public void createDirs() throws IOException {
        base   = Files.createTempDirectory("preflight").toRealPath();
        app    = Files.createDirectories(base.resolve("app"));
        upload = Files.createDirectories(base.resolve("upload"));
    }

    @After
    public void deleteDirs() throws IOException {
        TempDirs.delete(base);
    }

    // ---------------------------------------------------------------- scope

    @Test
    public void config_without_owner_or_mode_is_not_checked() throws IOException {
        chmod(app, "rwxrwxrwx");

        assertPasses(""
                + "  - directoryOrCreate:\n"
                + "      destination: ./state\n");
    }

    @Test
    public void usual_layout_passes() {
        assertPasses(""
                + "  - directoryOrCreate:\n"
                + "      destination: ./state\n"
                + "      owner: { user: " + ME + " }\n"
                + "      mode: \"0770\"\n"
                + "  - fileConfig:\n"
                + "      source: ./app.yml\n"
                + "      destination: /opt/app/app.yml\n");
    }

    // ---------------------------------------------------------------- rule 1: exclusive O

    @Test
    public void file_config_inside_owned_directory() {
        assertFails(""
                + OWNED_STATE
                + "  - fileConfig:\n"
                + "      source: ./state/app.yml\n"
                + "      destination: /opt/app/app.yml\n",
                "volumes[1].fileConfig", "inside or through", "volumes[0].directoryOrCreate");
    }

    /** Plan review 4, p. 1: the write ends outside O but goes through it. */
    @Test
    public void template_through_a_link_inside_owned_directory() throws IOException {
        Path state  = Files.createDirectories(app.resolve("state"));
        Path config = Files.createDirectories(app.resolve("config"));
        Files.createSymbolicLink(state.resolve("out"), config);

        assertFails(""
                + OWNED_STATE
                + "  - templateFileConfig:\n"
                + "      source: ./state/out/sshd_config\n"
                + "      destination: /opt/app/sshd_config\n",
                "volumes[1].templateFileConfig", "inside or through");
    }

    /** Plan review 3, p. 2: a recursive copy whose root contains O. */
    @Test
    public void dir_config_containing_owned_directory() throws IOException {
        Files.createDirectories(upload.resolve("seed"));

        assertFails(""
                + OWNED_STATE
                + "  - dirConfig:\n"
                + "      configPath: seed\n"
                + "      source: " + app + "\n"
                + "      destination: /opt/app\n",
                "volumes[1].dirConfig", "recursive copy contains");
    }

    /** Plan review 4, p. 2: separate roots, but a link inside the copy target leads into O. */
    @Test
    public void dir_config_with_internal_link_into_owned_directory() throws IOException {
        Files.createDirectories(app.resolve("state"));
        Path config = Files.createDirectories(app.resolve("config"));
        Files.createSymbolicLink(config.resolve("shared"), app.resolve("state"));
        Files.createDirectories(upload.resolve("seed/shared/sub"));
        Files.write(upload.resolve("seed/shared/sub/sshd_config"), new byte[0]);

        String volumes = ""
                + "  - dirConfig:\n"
                + "      configPath: seed\n"
                + "      source: ./config\n"
                + "      destination: /opt/app/config\n";

        assertFails(OWNED_STATE + volumes, "dirConfig → shared", "inside or through");
        assertFails(volumes + OWNED_STATE, "dirConfig → shared", "inside or through");
    }

    @Test
    public void source_base_dir_containing_owned_directory_is_fine() {
        assertPasses(OWNED_STATE);
    }

    @Test
    public void two_owned_volumes_on_one_directory_through_a_link() throws IOException {
        Files.createDirectories(app.resolve("state"));
        Files.createSymbolicLink(app.resolve("alias"), app);

        assertFails(""
                + OWNED_STATE
                + "  - directoryOrCreate:\n"
                + "      source: ./alias/state\n"
                + "      destination: /data\n"
                + "      owner: { user: " + ME + " }\n",
                "the same directory");
    }

    /** Plan review 2, p. 2: the verdict must not depend on the order of volumes. */
    @Test
    public void nested_owned_volumes_in_both_orders() {
        String nested = ""
                + "  - directoryOrCreate:\n"
                + "      destination: ./state/sub\n"
                + "      owner: { user: " + ME + " }\n";

        assertFails(OWNED_STATE + nested, "goes through");
        assertFails(nested + OWNED_STATE, "goes through");
    }

    /**
     * Code review 4, p. 3: {@code mount --bind state alias} — another path, the same directory.
     * Bind mounts need root, so the reader reports alias with the device/inode of state.
     */
    @Test
    public void bind_mount_alias_of_owned_directory() throws IOException {
        Path state = Files.createDirectories(app.resolve("state"));
        Path alias = Files.createDirectories(app.resolve("alias"));
        PathAttributes.Reader reader = bindMount(state, alias);

        assertThatThrownBy(() -> check(preflight(reader), ""
                + OWNED_STATE
                + "  - templateFileConfig:\n"
                + "      source: ./alias/sub/sshd_config\n"
                + "      destination: /opt/app/sshd_config\n"))
                .hasMessageContainingAll("volumes[1].templateFileConfig", "inside or through");

        assertThatThrownBy(() -> check(preflight(reader), ""
                + OWNED_STATE
                + "  - directoryOrCreate:\n"
                + "      source: ./alias\n"
                + "      destination: /data\n"
                + "      owner: { user: " + ME + " }\n"))
                .hasMessageContaining("the same directory");
    }

    /**
     * Code review 4-2, p. 1: O does not exist yet, an ancestor is bind-mounted elsewhere. The
     * anchor is the deepest existing directory (device/inode) plus the missing tail.
     */
    @Test
    public void bind_mount_alias_of_an_ancestor_of_owned_directory_not_created_yet() throws IOException {
        Path alias = Files.createDirectories(base.resolve("alias"));
        PathAttributes.Reader reader = bindMount(app, alias);

        assertThatThrownBy(() -> check(preflight(reader), ""
                + OWNED_STATE
                + "  - templateFileConfig:\n"
                + "      source: " + alias.resolve("state/sub/sshd_config") + "\n"
                + "      destination: /opt/app/sshd_config\n"))
                .hasMessageContainingAll("volumes[1].templateFileConfig", "inside or through");

        // another name under the alias is fine
        assertThatCode(() -> check(preflight(reader), ""
                + OWNED_STATE
                + "  - templateFileConfig:\n"
                + "      source: " + alias.resolve("other/sshd_config") + "\n"
                + "      destination: /opt/app/sshd_config\n"))
                .doesNotThrowAnyException();
    }

    /** Code review 4-2, p. 3: copyDir follows a link in the source, so the enumeration must too. */
    @Test
    public void dir_config_source_that_is_a_link() throws IOException {
        Files.createDirectories(app.resolve("state"));
        Path config = Files.createDirectories(app.resolve("config"));
        Files.createSymbolicLink(config.resolve("shared"), app.resolve("state"));
        Path assets = Files.createDirectories(base.resolve("assets/shared/sub"));
        Files.write(assets.resolve("sshd_config"), new byte[0]);
        Files.createSymbolicLink(upload.resolve("seed"), base.resolve("assets"));

        assertFails(OWNED_STATE
                + "  - dirConfig:\n"
                + "      configPath: seed\n"
                + "      source: ./config\n"
                + "      destination: /opt/app/config\n",
                "dirConfig → shared", "inside or through");
    }

    @Test
    public void dir_config_source_outside_the_task() {
        assertFails(OWNED_STATE
                + "  - dirConfig:\n"
                + "      configPath: ../seed\n"
                + "      source: ./config\n"
                + "      destination: /opt/app/config\n",
                "dirConfig.configPath (../seed)", "'..' is not allowed");
    }

    /**
     * Code review 4-3, p. 1: {@code mount --bind app app/ssh/x/etc}; O = app/ssh/x/etc/ssh is
     * app/ssh, an ancestor on its own path. Simulated: app/ssh/x/etc/ssh reports the inode of app/ssh.
     */
    @Test
    public void owned_directory_that_is_its_own_ancestor_through_a_bind_mount() throws IOException {
        Path ssh   = Files.createDirectories(app.resolve("ssh"));
        Path owned = Files.createDirectories(app.resolve("ssh/x/etc/ssh"));
        PathAttributes.Reader reader = bindMount(ssh, owned);

        assertThatThrownBy(() -> check(preflight(reader), ""
                + "  - directoryOrCreate:\n"
                + "      destination: ./ssh/x/etc/ssh\n"
                + "      owner: { user: " + ME + " }\n"))
                .hasMessageContaining("goes through the volume directory itself");
    }

    /** Code review 4, p. 1: under root the set of trusted uids must not contain 0 twice. */
    @Test
    public void trusted_uids_for_root_and_non_root_agent() {
        assertThat(WritePathPreflight.trustedUids(0)).containsExactly(0);
        assertThat(WritePathPreflight.trustedUids(501)).containsExactlyInAnyOrder(0, 501);
    }

    // ---------------------------------------------------------------- rule 0: shape

    /** Plan review 5: O going through itself. */
    @Test
    public void dot_dot_is_rejected() {
        assertFails(""
                + "  - directoryOrCreate:\n"
                + "      source: ./state/sub/..\n"
                + "      destination: /state\n"
                + "      owner: { user: " + ME + " }\n",
                "'.' and '..' are not allowed");
    }

    // ---------------------------------------------------------------- rule 2: trusted directories

    @Test
    public void world_writable_directory_on_the_way() throws IOException {
        Path shared = Files.createDirectories(app.resolve("shared"));
        chmod(shared, "rwxrwxrwx");

        assertFails(""
                + "  - directoryOrCreate:\n"
                + "      destination: ./shared/state\n"
                + "      owner: { user: " + ME + " }\n",
                "directory " + shared, "mode 0777", "can be changed");
    }

    @Test
    public void directory_of_a_foreign_owner_on_the_way() throws IOException {
        Path foreign = Files.createDirectories(app.resolve("foreign"));
        PathAttributes.Reader reader = path -> {
            PathAttributes real = PathAttributes.LSTAT.read(path);
            return path.equals(foreign) && real != null ? new PathAttributes(4242, real.gid(), fakeMode(real), 1, 1) : real;
        };

        assertThatThrownBy(() -> check(preflight(reader), ""
                + "  - directoryOrCreate:\n"
                + "      destination: ./foreign/state\n"
                + "      owner: { user: " + ME + " }\n"))
                .hasMessageContainingAll("directory " + foreign, "uid 4242");
    }

    @Test
    public void link_in_a_trusted_directory_is_fine() throws IOException {
        Path real = Files.createDirectories(base.resolve("real"));
        Files.createSymbolicLink(app.resolve("data"), real);

        assertPasses(""
                + "  - directoryOrCreate:\n"
                + "      destination: ./data/state\n"
                + "      owner: { user: " + ME + " }\n");
    }

    @Test
    public void sticky_directory_with_an_own_entry_is_fine() throws IOException {
        Path tmp = Files.createDirectories(app.resolve("tmp"));
        Files.createDirectories(tmp.resolve("svc"));
        sticky(tmp);

        assertPasses(""
                + "  - directoryOrCreate:\n"
                + "      destination: ./tmp/svc/state\n"
                + "      owner: { user: " + ME + " }\n");
    }

    /** Plan review 6: the owner of O could rename it inside a sticky parent. */
    @Test
    public void sticky_directory_as_the_direct_parent_of_owned() throws IOException {
        Path tmp = Files.createDirectories(app.resolve("tmp"));
        Files.createDirectories(tmp.resolve("state"));
        sticky(tmp);

        assertFails(""
                + "  - directoryOrCreate:\n"
                + "      destination: ./tmp/state\n"
                + "      owner: { user: " + ME + " }\n",
                "sticky directory is not allowed as the parent");
    }

    @Test
    public void missing_entry_in_a_sticky_directory_is_rejected() throws IOException {
        Path tmp = Files.createDirectories(app.resolve("tmp"));
        sticky(tmp);

        assertFails(OWNED_STATE
                + "  - fileConfig:\n"
                + "      source: ./tmp/new.yml\n"
                + "      destination: /opt/app/new.yml\n",
                "volumes[1].fileConfig", "directory " + tmp);
    }

    // ---------------------------------------------------------------- rule 3: O is not a link

    @Test
    public void owned_directory_that_is_a_link() throws IOException {
        Path real = Files.createDirectories(app.resolve("real"));
        Files.createSymbolicLink(app.resolve("state"), real);

        assertFails(OWNED_STATE, "is a symbolic link");
    }

    // ---------------------------------------------------------------- whole pipeline

    @Test
    public void all_violations_in_one_message() throws IOException {
        Path real = Files.createDirectories(app.resolve("real"));
        Files.createSymbolicLink(app.resolve("state"), real);

        assertFails(OWNED_STATE
                + "  - directoryOrCreate:\n"
                + "      source: ./x/..\n"
                + "      destination: /x\n"
                + "      owner: { user: " + ME + " }\n",
                "is a symbolic link", "'..' are not allowed");
    }

    /** Nothing is created or changed when the preflight fails — in either order of volumes. */
    @Test
    public void resolver_changes_nothing_on_failure() {
        String nested = ""
                + "  - directoryOrCreate:\n"
                + "      destination: ./state/sub\n"
                + "      owner: { user: " + ME + " }\n";

        for (String volumes : new String[]{OWNED_STATE + nested, nested + OWNED_STATE}) {
            RecordingFileSystem fileSystem = new RecordingFileSystem();
            TDocker unresolved = parse(volumes);

            assertThatThrownBy(() -> new DockerResolver().resolve(unresolved, upload.toFile(), fileSystem, (p, a) -> { }, preflight(PathAttributes.LSTAT)))
                    .hasMessageContaining("goes through");
            assertThat(fileSystem.calls).isEmpty();
        }
    }

    /**
     * Code review 4-4, p. 1: under umask 000 mkdirs creates 0777 directories after the preflight;
     * the owner must not be changed through them. Simulated by a file system that creates 0777.
     */
    @Test
    public void directories_created_writable_for_others_stop_before_the_owner_change() {
        RecordingFileSystem umask000 = new RecordingFileSystem() {
            @Override
            public void createDirectories(Owner aOwner, File aDir) {
                super.createDirectories(aOwner, aDir);
                try {
                    Path path = aDir.toPath();
                    Files.createDirectories(path);
                    for (Path p = path; !p.equals(app); p = p.getParent()) {
                        chmod(p, "rwxrwxrwx");
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        TDocker unresolved = parse(""
                + "  - directoryOrCreate:\n"
                + "      destination: ./new/hop/ssh\n"
                + "      owner: { user: " + ME + " }\n"
                + "      mode: \"0770\"\n");

        assertThatThrownBy(() -> new DockerResolver().resolve(unresolved, upload.toFile(), umask000, (p, a) -> { }, preflight(PathAttributes.LSTAT)))
                .hasMessageContainingAll("the owner was not changed", "can be changed by others");
        assertThat(umask000.calls).noneMatch(call -> call.startsWith("applyOwner"));
    }

    @Test
    public void root_directory_as_owned_volume() {
        assertFails(""
                + "  - directoryOrCreate:\n"
                + "      source: /\n"
                + "      destination: /host\n"
                + "      owner: { user: " + ME + " }\n",
                "the root directory cannot get owner/mode");
    }

    // ---------------------------------------------------------------- helpers

    private static final String OWNED_STATE = ""
            + "  - directoryOrCreate:\n"
            + "      destination: ./state\n"
            + "      owner: { user: " + ME + " }\n";

    private WritePathPreflight preflight(PathAttributes.Reader aReader) {
        Map<String, File> service = Map.of("service run", base.resolve("service/app/run").toFile());
        return new WritePathPreflight(service, WritePathPreflight.trustedUids(ME), aReader);
    }

    private TDocker parse(String aVolumesYaml) {
        return new YamlParser().parseText(""
                + "name: app\n"
                + "directories:\n"
                + "  sourceBaseDir: " + app + "\n"
                + "  destinationBaseDir: /opt/app\n"
                + "volumes:\n"
                + aVolumesYaml, TDocker.class);
    }

    private void check(WritePathPreflight aPreflight, String aVolumesYaml) {
        TDocker docker = parse(aVolumesYaml);
        aPreflight.check(docker.getVolumes(), upload.toFile(), docker.getDirectories());
    }

    private void assertPasses(String aVolumesYaml) {
        assertThatCode(() -> check(preflight(PathAttributes.LSTAT), aVolumesYaml)).doesNotThrowAnyException();
    }

    private void assertFails(String aVolumesYaml, String... aMessageParts) {
        assertThatThrownBy(() -> check(preflight(PathAttributes.LSTAT), aVolumesYaml))
                .hasMessageContaining("nothing was changed")
                .hasMessageContainingAll(aMessageParts);
    }

    private static void chmod(Path aPath, String aPermissions) throws IOException {
        Files.setPosixFilePermissions(aPath, PosixFilePermissions.fromString(aPermissions));
    }

    /** 1777, like /tmp. PosixFilePermissions has no sticky bit. */
    private static void sticky(Path aPath) throws IOException {
        Files.setAttribute(aPath, "unix:mode", 01777);
    }

    /** {@code aAlias} is reported as the same directory (device/inode) as {@code aTarget}. */
    private static PathAttributes.Reader bindMount(Path aTarget, Path aAlias) throws IOException {
        PathAttributes target = PathAttributes.LSTAT.read(aTarget);
        return path -> path.equals(aAlias) ? target : PathAttributes.LSTAT.read(path);
    }

    private static int fakeMode(PathAttributes aReal) {
        return (aReal.isDirectory() ? 0040000 : 0100000) | aReal.permissions() | (aReal.isSticky() ? 01000 : 0);
    }
}
