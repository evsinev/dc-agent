package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemCheckImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystemFactory;
import com.payneteasy.dcagent.core.modules.docker.preflight.TempDirs;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** dirConfig {@code sync}/{@code keep}: config errors fail CHECK and PUSH before anything is written. */
public class PushDockerActionDirSyncConfigTest {

    private static final String NAME = "dir-sync";

    private Path base;
    private Path conf;

    @Before
    public void createDirs() throws IOException {
        base = Files.createTempDirectory("push-dir-sync").toRealPath();
        conf = base.resolve("app/conf");
    }

    @After
    public void deleteDirs() throws IOException {
        TempDirs.delete(base);
    }

    @Test
    public void an_unknown_sync_value_is_an_error() throws IOException {
        assertRefused(dirConfig("      sync: MIRRO\n"), "volumes[0].dirConfig.sync: expected OVERLAY or MIRROR, got 'MIRRO'");
    }

    @Test
    public void sync_is_case_sensitive() throws IOException {
        assertRefused(dirConfig("      sync: mirror\n"), "volumes[0].dirConfig.sync: expected OVERLAY or MIRROR, got 'mirror'");
    }

    @Test
    public void sync_must_be_a_scalar() throws IOException {
        assertRefused(dirConfig("      sync: [MIRROR]\n"), "volumes[0].dirConfig.sync: expected OVERLAY or MIRROR, got BEGIN_ARRAY");
    }

    @Test
    public void keep_without_sync_is_an_error() throws IOException {
        assertRefused(dirConfig("      keep: [\"pki/*.key\"]\n"), "volumes[0].dirConfig.keep: allowed only with sync: MIRROR");
    }

    @Test
    public void keep_with_overlay_is_an_error() throws IOException {
        assertRefused(dirConfig("      sync: OVERLAY\n      keep: [\"pki/*.key\"]\n"), "volumes[0].dirConfig.keep: allowed only with sync: MIRROR");
    }

    @Test
    public void an_empty_keep_without_mirror_is_an_error() throws IOException {
        assertRefused(dirConfig("      keep: []\n"), "volumes[0].dirConfig.keep: allowed only with sync: MIRROR");
    }

    @Test
    public void every_invalid_keep_pattern_is_reported() throws IOException {
        String keep = "      sync: MIRROR\n"
                + "      keep:\n"
                + "        - \"/abs/*.key\"\n"
                + "        - \"../up/*\"\n"
                + "        - \"dir/\"\n"
                + "        - \"a\\\\b\"\n"
                + "        - \"./pki/*.key\"\n"
                + "        - \"\"\n"
                + "        - \"pki/[a\"\n";
        File zip = zip(dirConfig(keep));
        for (IFileSystemFactory factory : factories()) {
            assertThatThrownBy(() -> push(zip, factory))
                    .hasMessageContaining("volumes[0].dirConfig.keep[0] ('/abs/*.key'): must be relative to source")
                    .hasMessageContaining("volumes[0].dirConfig.keep[1] ('../up/*'): '.', '..' and empty components are not allowed")
                    .hasMessageContaining("volumes[0].dirConfig.keep[2] ('dir/'): must not end with '/'")
                    .hasMessageContaining("volumes[0].dirConfig.keep[3] ('a\\b'): backslash is not allowed")
                    .hasMessageContaining("volumes[0].dirConfig.keep[4] ('./pki/*.key'): '.', '..' and empty components")
                    .hasMessageContaining("volumes[0].dirConfig.keep[5] (''): must not be empty")
                    .hasMessageContaining("volumes[0].dirConfig.keep[6] ('pki/[a'): invalid glob");
        }
        assertNothingWritten();
    }

    @Test
    public void sync_on_another_volume_type_is_an_unknown_key() throws IOException {
        String volumes = "  - fileConfig:\n"
                + "      configPath: ./conf/site-a.conf\n"
                + "      source: " + conf.resolve("site-a.conf") + "\n"
                + "      destination: /opt/app/conf/site-a.conf\n"
                + "      sync: MIRROR\n";
        assertRefused(volumes, "volumes[0].fileConfig.sync: unknown key");
    }

    @Test
    public void a_mirror_element_with_two_volume_types_is_an_error() throws IOException {
        // A missing '-': source would come from directoryOrCreate while dirConfig runs
        String volumes = "  - directoryOrCreate:\n"
                + "      source: " + base.resolve("shared") + "\n"
                + "      destination: /shared\n"
                + "    dirConfig:\n"
                + "      configPath: ./conf\n"
                + "      source: " + conf + "\n"
                + "      destination: /opt/app/conf\n"
                + "      sync: MIRROR\n";
        assertRefused(volumes, "volumes[0].dirConfig: one list element declares several volume types [directoryOrCreate, dirConfig]");
        assertThat(base.resolve("shared")).doesNotExist();
    }

    @Test
    public void without_sync_dir_config_still_overlays() throws IOException {
        Files.createDirectories(conf);
        Files.writeString(conf.resolve("stale.conf"), "stale");

        push(zip(dirConfig("")), FileSystemWriterImpl::new);

        assertThat(conf.resolve("site-a.conf")).hasContent("site a");
        assertThat(conf.resolve("stale.conf")).hasContent("stale");
    }

    @Test
    public void explicit_overlay_overlays() throws IOException {
        Files.createDirectories(conf);
        Files.writeString(conf.resolve("stale.conf"), "stale");

        push(zip(dirConfig("      sync: OVERLAY\n")), FileSystemWriterImpl::new);

        assertThat(conf.resolve("site-a.conf")).hasContent("site a");
        assertThat(conf.resolve("stale.conf")).hasContent("stale");
    }

    private String dirConfig(String aExtra) {
        return "  - dirConfig:\n"
                + "      configPath: ./conf\n"
                + "      source: " + conf + "\n"
                + "      destination: /opt/app/conf\n"
                + aExtra;
    }

    private void assertRefused(String aVolumes, String aMessage) throws IOException {
        File zip = zip(aVolumes);
        for (IFileSystemFactory factory : factories()) {
            assertThatThrownBy(() -> push(zip, factory)).hasMessageContaining(aMessage);
        }
        assertNothingWritten();
    }

    private void assertNothingWritten() {
        assertThat(base.resolve("service")).doesNotExist();
        assertThat(conf).doesNotExist();
    }

    private static IFileSystemFactory[] factories() {
        return new IFileSystemFactory[]{FileSystemCheckImpl::new, FileSystemWriterImpl::new};
    }

    private void push(File aZip, IFileSystemFactory aFactory) {
        TempDir               tempDir     = new TempDir(base.resolve("tmp").toFile(), true);
        ServicesDefinitionDir servicesDir = new ServicesDefinitionDir(base.resolve("service").toFile());
        ServicesLogDir        logDir      = new ServicesLogDir(base.resolve("log").toFile());

        new PushDockerAction(NAME, tempDir, servicesDir, logDir, (aPattern, args) -> { }, aFactory).pushService(aZip);
    }

    private File zip(String aVolumes) throws IOException {
        String yaml = "version: 0.0.1\n"
                + "name: " + NAME + "\n"
                + "image:\n"
                + "  name: \"hello-world:latest\"\n"
                + "directories:\n"
                + "  sourceBaseDir: " + base.resolve("app") + "\n"
                + "  destinationBaseDir: /opt/app\n"
                + "volumes:\n"
                + aVolumes;

        File zip = base.resolve("task.zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            put(out, "dc-docker.yml", yaml);
            put(out, "conf/site-a.conf", "site a");
        }
        return zip;
    }

    private static void put(ZipOutputStream aOut, String aName, String aText) throws IOException {
        aOut.putNextEntry(new ZipEntry(aName));
        aOut.write(aText.getBytes(UTF_8));
        aOut.closeEntry();
    }
}
