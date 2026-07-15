package com.payneteasy.dcagent.core.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

public class StreamsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void copy_transfers_all_bytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Streams.copy(new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8)), out);

        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("hello world");
    }

    @Test
    public void write_file_stores_stream_content() throws Exception {
        File target = new File(folder.getRoot(), "out.txt");

        Streams.writeFile(target, new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

        assertThat(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8)).isEqualTo("data");
    }

    @Test
    public void write_to_temp_file_creates_a_readable_file_with_content() {
        File temp = Streams.writeToTempFile(
                new ByteArrayInputStream("temp-content".getBytes(StandardCharsets.UTF_8)), "prefix-", ".tmp");

        assertThat(temp).exists();
        temp.deleteOnExit();
    }

    @Test
    public void write_to_temp_file_writes_the_exact_bytes() throws Exception {
        File temp = Streams.writeToTempFile(
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), "prefix-", ".tmp");
        temp.deleteOnExit();

        assertThat(new String(Files.readAllBytes(temp.toPath()), StandardCharsets.UTF_8)).isEqualTo("abc");
    }

    @Test
    public void read_all_bytes_returns_everything_across_buffer_boundaries() {
        byte[] large = new byte[10_000];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 127);
        }

        byte[] result = Streams.readAllBytes(new ByteArrayInputStream(large));

        assertThat(result).isEqualTo(large);
    }

    @Test
    public void read_all_bytes_of_empty_stream_returns_empty_array() {
        assertThat(Streams.readAllBytes(new ByteArrayInputStream(new byte[0]))).isEmpty();
    }
}
