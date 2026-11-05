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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** dirConfig {@code sync: MIRROR} through DOCKER_CHECK and DOCKER_PUSH on a real directory tree. */
public class PushDockerActionMirrorTest {

    private static final String NAME = "mirror";

    private Path base;
    private Path app;
    private Path conf;

    private final Map<String, String> task = new LinkedHashMap<>();

    private String sourceBaseDir;

    @Before
    public void createDirs() throws IOException {
        base = Files.createTempDirectory("push-mirror").toRealPath();
        app  = Files.createDirectories(base.resolve("app"));
        conf = app.resolve("conf");
        task.put("conf/conf.d/site-a.conf", "site a");
        task.put("conf/pki/site-a.crt"    , "crt a");
        task.put("conf/main.conf"         , "main");
    }

    @After
    public void deleteDirs() throws IOException {
        TempDirs.delete(base);
    }

    @Test
    public void overlay_leaves_an_extra_host_file() throws IOException {
        write("conf.d/site-b.conf", "site b");

        assertThat(check(volumes(dirConfig("")))).isEmpty();
        push(volumes(dirConfig("")));

        assertThat(conf.resolve("conf.d/site-b.conf")).hasContent("site b");
    }

    @Test
    public void mirror_deletes_extra_files_and_directories_and_check_lists_exactly_them() throws IOException {
        write("conf.d/site-a.conf", "old site a");
        write("conf.d/site-b.conf", "site b");
        write("pki/site-b.crt"    , "crt b");
        write("old/nested/x.conf" , "x");
        write("old/y.conf"        , "y");

        List<String> checked = check(volumes(mirror("")));
        assertThat(checked).containsExactlyInAnyOrder(
                  conf.resolve("conf.d/site-b.conf").toString()
                , conf.resolve("pki/site-b.crt").toString()
                , conf.resolve("old/nested/x.conf").toString()
                , conf.resolve("old/nested") + "/"
                , conf.resolve("old/y.conf").toString()
                , conf.resolve("old") + "/"
        );
        // check changes nothing
        assertThat(conf.resolve("conf.d/site-b.conf")).exists();

        List<String> deleted = push(volumes(mirror("")));

        assertThat(deleted).containsExactlyElementsOf(checked);
        assertThat(conf.resolve("conf.d/site-a.conf")).hasContent("site a");
        assertThat(conf.resolve("pki/site-a.crt")).hasContent("crt a");
        assertThat(conf.resolve("main.conf")).hasContent("main");
        assertThat(conf.resolve("conf.d/site-b.conf")).doesNotExist();
        assertThat(conf.resolve("pki/site-b.crt")).doesNotExist();
        assertThat(conf.resolve("old")).doesNotExist();
        assertThat(conf.resolve("conf.d")).isDirectory();

        assertThat(check(volumes(mirror("")))).isEmpty();
    }

    @Test
    public void the_check_text_of_a_pending_deletion_is_a_drift() throws IOException {
        write("conf.d/site-b.conf", "site b");
        push(volumes(dirConfig("")));

        List<String> lines = new ArrayList<>();
        run(volumes(mirror("")), FileSystemCheckImpl::new, (aPattern, args) -> lines.add(aPattern));

        assertThat(lines).anyMatch(line -> line.contains("Will delete"));
    }

    @Test
    public void keep_protects_matching_entries() throws IOException {
        write("pki/site-a.key"   , "key a");
        write("pki/site-b.key"   , "key b");
        write("pki/site-b.crt"   , "crt b");
        write("local/one.conf"   , "1");
        write("local/deep/two"   , "2");
        write("conf.d/site-b.key", "not in pki");

        String keep = "      keep:\n        - \"pki/*.key\"\n        - \"local/**\"\n";
        List<String> checked = check(volumes(mirror(keep)));
        List<String> deleted = push(volumes(mirror(keep)));

        assertThat(deleted).containsExactlyElementsOf(checked).containsExactlyInAnyOrder(
                  conf.resolve("pki/site-b.crt").toString()
                , conf.resolve("conf.d/site-b.key").toString()
        );
        assertThat(conf.resolve("pki/site-a.key")).hasContent("key a");
        assertThat(conf.resolve("pki/site-b.key")).hasContent("key b");
        assertThat(conf.resolve("local/one.conf")).hasContent("1");
        assertThat(conf.resolve("local/deep/two")).hasContent("2");
    }

    @Test
    public void a_keep_directory_pattern_keeps_its_subtree() throws IOException {
        write("local/deep/two", "2");

        push(volumes(mirror("      keep: [\"local\"]\n")));

        assertThat(conf.resolve("local/deep/two")).hasContent("2");
    }

    @Test
    public void a_symbolic_link_is_deleted_itself_and_its_target_is_untouched() throws IOException {
        Path outsideFile = Files.writeString(Files.createDirectories(base.resolve("outside")).resolve("secret"), "secret");
        Path outsideDir  = Files.createDirectories(base.resolve("outside/dir"));
        Files.writeString(outsideDir.resolve("inner"), "inner");
        Files.createDirectories(conf);
        Files.createSymbolicLink(conf.resolve("file-link"), outsideFile);
        Files.createSymbolicLink(conf.resolve("dir-link"), outsideDir);
        Files.createSymbolicLink(conf.resolve("dangling"), base.resolve("nowhere"));

        List<String> deleted = push(volumes(mirror("")));

        assertThat(deleted).containsExactlyInAnyOrder(
                  conf.resolve("dangling").toString()
                , conf.resolve("dir-link").toString()
                , conf.resolve("file-link").toString()
        );
        assertThat(outsideFile).hasContent("secret");
        assertThat(outsideDir.resolve("inner")).hasContent("inner");
    }

    @Test
    public void volumes_below_the_root_are_not_touched() throws IOException {
        write("state/data"     , "state");
        write("shared/data"    , "shared");
        write("single.conf"    , "single old");
        task.put("single.conf" , "single");

        String volumes = volumes(mirror(""))
                + "  - directoryOrCreate:\n"
                + "      source: " + conf.resolve("state") + "\n"
                + "      destination: /state\n"
                + "  - linkToHostDirectory:\n"
                + "      source: " + conf.resolve("shared/data") + "\n"
                + "      destination: /shared\n"
                + "  - directoryOrCreate:\n"
                + "      source: " + conf.resolve("later/created") + "\n"
                + "      destination: /later\n";
        task.remove("single.conf");
        Files.createDirectories(conf.resolve("later"));

        List<String> deleted = push(volumes);

        assertThat(deleted).containsExactly(conf.resolve("single.conf").toString());
        assertThat(conf.resolve("state/data")).hasContent("state");
        assertThat(conf.resolve("shared/data")).hasContent("shared");
        assertThat(conf.resolve("later/created")).isDirectory();
    }

    @Test
    public void a_volume_reaching_the_root_through_a_symbolic_link_is_protected() throws IOException {
        write("local/data", "local");
        Files.createSymbolicLink(base.resolve("alias"), conf);

        String volumes = volumes(mirror(""))
                + "  - directoryOrCreate:\n"
                + "      source: " + base.resolve("alias/local") + "\n"
                + "      destination: /local\n";

        assertThat(push(volumes)).isEmpty();
        assertThat(conf.resolve("local/data")).hasContent("local");
    }

    @Test
    public void a_hard_link_to_a_protected_file_is_protected() throws IOException {
        write("state/data", "state");
        Files.createLink(conf.resolve("hard-link"), conf.resolve("state/data"));

        String volumes = volumes(mirror(""))
                + "  - linkToHostFile:\n"
                + "      source: " + conf.resolve("state/data") + "\n"
                + "      destination: /data\n";

        assertThat(push(volumes)).isEmpty();
        assertThat(conf.resolve("hard-link")).hasContent("state");
    }

    @Test
    public void an_overlay_dir_config_above_the_root_is_an_error() throws IOException {
        write("extra.conf", "extra");
        String volumes = volumes(mirror(""))
                + "  - dirConfig:\n"
                + "      configPath: ./conf\n"
                + "      source: " + app + "\n"
                + "      destination: /opt/app\n";

        assertRefusedUnchanged(volumes, "volumes[0].dirConfig: the MIRROR root " + conf + " is inside the dirConfig of volumes[1]");
    }

    @Test
    public void another_volume_on_the_root_itself_is_an_error() throws IOException {
        write("extra.conf", "extra");
        String volumes = volumes(mirror(""))
                + "  - linkToHostDirectory:\n"
                + "      source: " + conf + "\n"
                + "      destination: /again\n";

        assertRefusedUnchanged(volumes, "volumes[0].dirConfig: the MIRROR root " + conf + " is also used by volumes[1].linkToHostDirectory");
    }

    @Test
    public void a_file_where_the_task_has_a_directory_is_an_error_before_any_change() throws IOException {
        write("conf.d", "a file");
        write("extra.conf", "extra");

        assertRefusedUnchanged(volumes(mirror("")), conf.resolve("conf.d") + " is a file, the task has a directory there");
        assertThat(conf.resolve("main.conf")).doesNotExist();
    }

    @Test
    public void a_symbolic_link_where_the_task_has_a_file_is_an_error() throws IOException {
        Path outside = Files.writeString(base.resolve("outside.conf"), "outside");
        Files.createDirectories(conf);
        Files.createSymbolicLink(conf.resolve("main.conf"), outside);

        assertRefusedUnchanged(volumes(mirror("")), conf.resolve("main.conf") + " is a symbolic link, the task has a file there");
        assertThat(outside).hasContent("outside");
    }

    @Test
    public void a_name_differing_only_by_case_is_an_error() throws IOException {
        task.remove("conf/main.conf");
        task.put("conf/site.conf", "site");
        write("SITE.conf", "site");

        assertRefusedUnchanged(volumes(mirror("")), "SITE.conf differs from 'site.conf' in the task only by case or Unicode normalization");
    }

    @Test
    public void a_missing_config_path_is_an_error() throws IOException {
        write("extra.conf", "extra");
        String volumes = "  - dirConfig:\n"
                + "      configPath: ./confx\n"
                + "      source: " + conf + "\n"
                + "      destination: /opt/app/conf\n"
                + "      sync: MIRROR\n";

        assertRefusedUnchanged(volumes, "volumes[0].dirConfig.configPath (./confx): no such directory in the task");
    }

    @Test
    public void a_config_path_outside_the_task_is_an_error() throws IOException {
        write("extra.conf", "extra");
        String volumes = "  - dirConfig:\n"
                + "      configPath: ../../conf\n"
                + "      source: " + conf + "\n"
                + "      destination: /opt/app/conf\n"
                + "      sync: MIRROR\n";

        assertRefusedUnchanged(volumes, "volumes[0].dirConfig.configPath (../../conf): must be inside the task");
    }

    @Test
    public void mirror_on_a_top_level_directory_is_an_error() throws IOException {
        String volumes = "  - dirConfig:\n"
                + "      configPath: ./conf\n"
                + "      source: /dc-agent-mirror-test-top-level\n"
                + "      destination: /opt/app/conf\n"
                + "      sync: MIRROR\n";

        assertRefused(volumes, "volumes[0].dirConfig.source (/dc-agent-mirror-test-top-level): sync: MIRROR is not allowed on / or a top-level directory");
    }

    @Test
    public void mirror_on_the_source_base_dir_is_an_error() throws IOException {
        Files.writeString(app.resolve("keep-me"), "base");
        String volumes = "  - dirConfig:\n"
                + "      configPath: ./conf\n"
                + "      source: ./\n"
                + "      destination: /opt/app/conf\n"
                + "      sync: MIRROR\n";

        assertRefused(volumes, "sync: MIRROR is not allowed on directories.sourceBaseDir");
        assertThat(app.resolve("keep-me")).hasContent("base");
    }

    @Test
    public void a_root_writable_by_others_is_an_error() throws IOException {
        write("extra.conf", "extra");
        Files.setPosixFilePermissions(conf, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertRefusedUnchanged(volumes(mirror("")), "can be changed by others than root and the agent");
    }

    @Test
    public void a_parent_writable_by_others_is_an_error() throws IOException {
        write("extra.conf", "extra");
        Files.setPosixFilePermissions(app, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertRefusedUnchanged(volumes(mirror("")), "on the way can be changed by others than root and the agent");
    }

    @Test
    public void a_missing_root_is_created_and_nothing_is_deleted() throws IOException {
        assertThat(check(volumes(mirror("")))).isEmpty();
        assertThat(push(volumes(mirror("")))).isEmpty();
        assertThat(conf.resolve("main.conf")).hasContent("main");
    }

    @Test
    public void a_control_character_in_a_host_name_is_escaped_in_the_log() throws IOException {
        write("evil\nname", "x");

        assertThat(check(volumes(mirror("")))).containsExactly(conf.resolve("evil_name").toString());
    }

    @Test
    public void dot_dot_in_the_mirror_source_is_an_error() throws IOException {
        // alias/.. is the parent of the link's target, not app: copy and deletion would part ways
        Path other = Files.createDirectories(base.resolve("other/child"));
        Files.createDirectories(base.resolve("other/conf"));
        Files.writeString(base.resolve("other/conf/victim"), "victim");
        Files.createSymbolicLink(app.resolve("alias"), other);
        write("extra.conf", "extra");
        String volumes = "  - dirConfig:\n"
                + "      configPath: ./conf\n"
                + "      source: " + app.resolve("alias/../conf") + "\n"
                + "      destination: /opt/app/conf\n"
                + "      sync: MIRROR\n";

        assertRefusedUnchanged(volumes, "'.' and '..' are not allowed with sync: MIRROR");
    }

    @Test
    public void a_symbolic_link_on_the_way_to_another_volume_is_not_deleted() throws IOException {
        Path shared = Files.createDirectories(base.resolve("shared"));
        Files.writeString(shared.resolve("key"), "key");
        Files.createDirectories(conf);
        Files.createSymbolicLink(conf.resolve("shared"), shared);

        String volumes = volumes(mirror(""))
                + "  - linkToHostFile:\n"
                + "      source: " + conf.resolve("shared/key") + "\n"
                + "      destination: /key\n";

        assertThat(check(volumes)).isEmpty();
        assertThat(push(volumes)).isEmpty();
        assertThat(conf.resolve("shared")).isSymbolicLink();
        assertThat(shared.resolve("key")).hasContent("key");
    }

    @Test
    public void a_merged_neighbour_element_is_an_error_in_a_mirror_task() throws IOException {
        write("extra.conf", "extra");
        String volumes = "  - directoryOrCreate:\n"
                + "      source: " + app + "\n"
                + "      destination: /opt/app\n"
                + "    dirConfig:\n"
                + "      configPath: ./conf\n"
                + "      source: " + app + "\n"
                + "      destination: /opt/app\n"
                + volumes(mirror(""));

        assertRefusedUnchanged(volumes, "volumes[0]: one list element declares several volume types [directoryOrCreate, dirConfig]");
    }

    @Test
    public void a_volume_reaching_the_root_through_a_link_and_dot_dot_is_protected() throws IOException {
        write("local/child/x", "x");
        write("local/key", "key");
        Files.createSymbolicLink(base.resolve("alias"), conf.resolve("local/child"));

        String volumes = volumes(mirror(""))
                + "  - linkToHostFile:\n"
                + "      source: " + base.resolve("alias/../key") + "\n"
                + "      destination: /key\n";

        List<String> deleted = push(volumes);

        assertThat(conf.resolve("local/key")).hasContent("key");
        // local/child is on the way of alias/.. — kept, its content is not
        assertThat(deleted).containsExactly(conf.resolve("local/child/x").toString());
        assertThat(conf.resolve("local/child")).isDirectory();
    }

    @Test
    public void mirror_on_a_source_base_dir_reached_through_a_link_and_dot_dot_is_an_error() throws IOException {
        Path releases = Files.createDirectories(base.resolve("releases/v1"));
        Path realBase = Files.createDirectories(base.resolve("releases/conf"));
        Files.writeString(realBase.resolve("keep-me"), "base");
        Files.createSymbolicLink(app.resolve("current"), releases);
        String yaml = "  - dirConfig:\n"
                + "      configPath: ./conf\n"
                + "      source: " + realBase + "\n"
                + "      destination: /opt/app/conf\n"
                + "      sync: MIRROR\n";
        sourceBaseDir = app.resolve("current/../conf").toString();

        assertRefusedUnchanged(yaml, "sync: MIRROR is not allowed on directories.sourceBaseDir");
        assertThat(realBase.resolve("keep-me")).hasContent("base");
    }

    // --- helpers

    private void write(String aRelative, String aText) throws IOException {
        Path file = conf.resolve(aRelative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, aText);
    }

    private String dirConfig(String aExtra) {
        return "  - dirConfig:\n"
                + "      configPath: ./conf\n"
                + "      source: " + conf + "\n"
                + "      destination: /opt/app/conf\n"
                + aExtra;
    }

    private String mirror(String aExtra) {
        return dirConfig("      sync: MIRROR\n" + aExtra);
    }

    private static String volumes(String aVolumes) {
        return aVolumes;
    }

    private void assertRefused(String aVolumes, String aMessage) {
        for (IFileSystemFactory factory : new IFileSystemFactory[]{FileSystemCheckImpl::new, FileSystemWriterImpl::new}) {
            assertThatThrownBy(() -> run(aVolumes, factory, (aPattern, args) -> { })).hasMessageContaining(aMessage);
        }
        assertThat(base.resolve("service")).doesNotExist();
    }

    private void assertRefusedUnchanged(String aVolumes, String aMessage) throws IOException {
        Map<Path, String> before = snapshot();
        assertRefused(aVolumes, aMessage);
        assertThat(snapshot()).isEqualTo(before);
    }

    private Map<Path, String> snapshot() throws IOException {
        Map<Path, String> files = new LinkedHashMap<>();
        if (!Files.exists(base)) {
            return files;
        }
        try (var stream = Files.walk(base)) {
            for (Path path : (Iterable<Path>) stream.sorted()::iterator) {
                if (path.startsWith(base.resolve("tmp")) || path.getFileName().toString().endsWith(".zip")) {
                    continue;
                }
                files.put(path, Files.isSymbolicLink(path) ? "-> " + Files.readSymbolicLink(path)
                        : Files.isRegularFile(path) ? Files.readString(path) : "dir");
            }
        }
        return files;
    }

    /** @return the paths CHECK would delete */
    private List<String> check(String aVolumes) throws IOException {
        return deletions(aVolumes, FileSystemCheckImpl::new, "Will delete");
    }

    /** @return the paths PUSH deleted */
    private List<String> push(String aVolumes) throws IOException {
        return deletions(aVolumes, FileSystemWriterImpl::new, "Deleted");
    }

    private List<String> deletions(String aVolumes, IFileSystemFactory aFactory, String aMarker) throws IOException {
        List<String> paths = new ArrayList<>();
        run(aVolumes, aFactory, (aPattern, args) -> {
            if (aPattern.contains(aMarker)) {
                paths.add(String.valueOf(args[0]));
            }
        });
        return paths;
    }

    private void run(String aVolumes, IFileSystemFactory aFactory, IActionLogger aLogger) throws IOException {
        File                  zip         = zip(aVolumes);
        TempDir               tempDir     = new TempDir(base.resolve("tmp").toFile(), true);
        ServicesDefinitionDir servicesDir = new ServicesDefinitionDir(base.resolve("service").toFile());
        ServicesLogDir        logDir      = new ServicesLogDir(base.resolve("log").toFile());

        new PushDockerAction(NAME, tempDir, servicesDir, logDir, aLogger, aFactory).pushService(zip);
    }

    private File zip(String aVolumes) throws IOException {
        String yaml = "version: 0.0.1\n"
                + "name: " + NAME + "\n"
                + "image:\n"
                + "  name: \"hello-world:latest\"\n"
                + "directories:\n"
                + "  sourceBaseDir: " + (sourceBaseDir != null ? sourceBaseDir : app.toString()) + "\n"
                + "  destinationBaseDir: /opt/app\n"
                + "volumes:\n"
                + aVolumes;

        File zip = Files.createTempFile(base, "task", ".zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            put(out, "dc-docker.yml", yaml);
            for (Map.Entry<String, String> entry : task.entrySet()) {
                put(out, entry.getKey(), entry.getValue());
            }
        }
        return zip;
    }

    private static void put(ZipOutputStream aOut, String aName, String aText) throws IOException {
        aOut.putNextEntry(new ZipEntry(aName));
        aOut.write(aText.getBytes(UTF_8));
        aOut.closeEntry();
    }
}
