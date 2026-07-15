package com.payneteasy.dcagent.core.modules.zipachive;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

public class TempFileTest {

    @Test
    public void creates_a_temp_file_on_construction() throws Exception {
        try (TempFile temp = new TempFile("unit", "tmp")) {
            assertThat(temp.getFile()).exists();
        }
    }

    @Test
    public void writes_stream_content_to_the_file() throws Exception {
        try (TempFile temp = new TempFile("unit", "tmp")) {
            temp.writeFromInputStream(new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)));

            assertThat(new String(Files.readAllBytes(temp.getFile().toPath()), StandardCharsets.UTF_8))
                    .isEqualTo("payload");
        }
    }

    @Test
    public void close_deletes_the_file() throws Exception {
        TempFile temp = new TempFile("unit", "tmp");
        java.io.File file = temp.getFile();

        temp.close();

        assertThat(file).doesNotExist();
    }
}
