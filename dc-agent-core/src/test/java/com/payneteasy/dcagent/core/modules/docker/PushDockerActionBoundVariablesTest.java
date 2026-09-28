package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemCheckImpl;
import com.payneteasy.dcagent.core.util.DeleteDirRecursively;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatCode;
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

        assertThatCode(() -> check(zip)).doesNotThrowAnyException();
    }

    @Test
    public void typed_field_is_validated_after_substitution() throws IOException {
        File zip = zip(
                "securityContext:\n"
                + "  runAsUser: \"{{ MISSING }}\"\n"
        );

        assertThatThrownBy(() -> check(zip))
                .hasMessageContaining("securityContext.runAsUser");
    }

    private void check(File aZip) {
        TempDir               tempDir     = new TempDir(new File(ROOT, "tmp"), true).createDir();
        ServicesDefinitionDir servicesDir = new ServicesDefinitionDir(new File(ROOT, "service"));
        ServicesLogDir        logDir      = new ServicesLogDir(new File(ROOT, "log"));

        new PushDockerAction("bound", tempDir, servicesDir, logDir, new ActionLoggerImpl(), FileSystemCheckImpl::new)
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
