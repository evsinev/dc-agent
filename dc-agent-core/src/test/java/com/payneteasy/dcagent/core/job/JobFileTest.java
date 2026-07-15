package com.payneteasy.dcagent.core.job;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

public class JobFileTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final byte[] TASK_BYTES = "TASK-ZIP-CONTENT".getBytes(StandardCharsets.UTF_8);

    private File buildJobZip() throws Exception {
        File zip = folder.newFile("job.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            put(out, "task.zip", TASK_BYTES);
            put(out, "job.json", "{}".getBytes(StandardCharsets.UTF_8));
            put(out, "job-signature.json", "{}".getBytes(StandardCharsets.UTF_8));
        }
        return zip;
    }

    private static void put(ZipOutputStream out, String name, byte[] body) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(body);
        out.closeEntry();
    }

    @Test
    public void reads_task_bytes_from_the_zip() throws Exception {
        assertThat(new JobFile(buildJobZip()).getTaskBytes()).isEqualTo(TASK_BYTES);
    }

    @Test
    public void parses_job_definition_and_signature() throws Exception {
        JobFile jobFile = new JobFile(buildJobZip());

        assertThat(jobFile.getJobDefinition()).isNotNull();
        assertThat(jobFile.getJobSignature()).isNotNull();
    }

    @Test
    public void job_bytes_getter_returns_a_defensive_copy() throws Exception {
        JobFile jobFile = new JobFile(buildJobZip());

        jobFile.getJobBytes()[0] = 0;

        assertThat(jobFile.getJobBytes()).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
    }
}
