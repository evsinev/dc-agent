package com.payneteasy.dcagent.core.util.gson;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GsonReaderTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final GsonReader reader = new GsonReader(new Gson());

    private File write(String json) throws Exception {
        File file = folder.newFile();
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    public static class Point {
        int x;
        int y;
    }

    @Test
    public void load_file_parses_json_into_type() throws Exception {
        Point point = reader.loadFile(write("{\"x\":3,\"y\":7}"), Point.class);

        assertThat(point.x).isEqualTo(3);
        assertThat(point.y).isEqualTo(7);
    }

    @Test
    public void load_json_object_parses_object() throws Exception {
        JsonObject object = reader.loadJsonObject(write("{\"name\":\"dc-agent\"}"));

        assertThat(object.get("name").getAsString()).isEqualTo("dc-agent");
    }

    @Test
    public void load_file_of_missing_file_throws_unchecked_io() {
        File missing = new File(folder.getRoot(), "does-not-exist.json");

        assertThatThrownBy(() -> reader.loadFile(missing, Point.class))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    public void load_json_object_of_missing_file_throws_unchecked_io() {
        File missing = new File(folder.getRoot(), "nope.json");

        assertThatThrownBy(() -> reader.loadJsonObject(missing))
                .isInstanceOf(UncheckedIOException.class);
    }
}
