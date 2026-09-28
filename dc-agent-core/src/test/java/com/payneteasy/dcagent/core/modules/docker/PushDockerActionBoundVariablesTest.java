package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemCheckImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystemFactory;
import com.payneteasy.dcagent.core.util.DeleteDirRecursively;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The raw dc-docker.yml is read before Handlebars substitution only for its bound variables;
 * typed fields such as {@code runAsUser} are parsed after substitution.
 */
public class PushDockerActionBoundVariablesTest {

    private static final File ROOT = new File("./target/push-bound-variables");

    @Before
    public void clean() {
        new DeleteDirRecursively(ROOT.getParentFile()).deleteDirIfExists(ROOT);
        ROOT.mkdirs();
    }

    @Test
    public void run_as_user_from_bound_variable() throws IOException {
        File zip = zip(
                "boundVariablesMap:\n"
                + "  UID: \"1001\"\n"
                + "  RO: \"true\"\n"
                + "securityContext:\n"
                + "  runAsUser: \"{{ UID }}\"\n"
                + "  readOnlyRootFilesystem: \"{{ RO }}\"\n"
        );

        push(zip, FileSystemWriterImpl::new);

        String run = new String(Files.readAllBytes(new File(ROOT, "service/bound/run").toPath()), UTF_8);
        assertThat(run).contains("  --user 1001 \\\n");
        assertThat(run).contains("  --read-only \\\n");
    }

    @Test
    public void typed_field_is_validated_after_substitution() throws IOException {
        File zip = zip(
                "securityContext:\n"
                + "  runAsUser: \"{{ MISSING }}\"\n"
        );

        assertThatThrownBy(() -> push(zip, FileSystemCheckImpl::new))
                .hasMessageContaining("securityContext.runAsUser");
    }

    private void push(File aZip, IFileSystemFactory aFileSystemFactory) {
        TempDir               tempDir     = new TempDir(new File(ROOT, "tmp"), true).createDir();
        ServicesDefinitionDir servicesDir = new ServicesDefinitionDir(new File(ROOT, "service"));
        ServicesLogDir        logDir      = new ServicesLogDir(new File(ROOT, "log"));

        new PushDockerAction("bound", tempDir, servicesDir, logDir, new ActionLoggerImpl(), aFileSystemFactory)
                .pushService(aZip);
    }

    private static File zip(String aExtraYaml) throws IOException {
        String yaml = "version: 0.0.1\n"
                + "name: bound\n"
                + "image:\n"
                + "  name: \"hello-world:latest\"\n"
                + "volumes: []\n"
                + aExtraYaml;

        File zip = new File(ROOT, "task.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("dc-docker.yml"));
            out.write(yaml.getBytes(UTF_8));
            out.closeEntry();
        }
        return zip;
    }
}
