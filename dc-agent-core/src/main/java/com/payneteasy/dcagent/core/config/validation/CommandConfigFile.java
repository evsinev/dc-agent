package com.payneteasy.dcagent.core.config.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.internal.LazilyParsedNumber;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.payneteasy.dcagent.core.yaml2json.Yaml2GsonConverter;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Reads one command config file ({@code <name>.json} / {@code <name>.yml} in CONFIG_DIR) into a
 * {@link JsonObject} — the one way the agent reads it, at start, in {@code check-config} and on a
 * request:
 * <ul>
 *     <li>a regular file, not a symbolic link (a link would be replaced by the migration while its
 *     target, with the plaintext keys, stays in the directory);</li>
 *     <li>at most {@link #MAX_BYTES}, UTF-8;</li>
 *     <li>a name repeated within one object is refused, before the object is built — JSON and YAML
 *     both keep the last of two {@code apiKeys}, so a plaintext key in the first would survive the
 *     migration unseen.</li>
 * </ul>
 * Messages carry the field path, never the names inside {@code apiKeys} (they are the secrets).
 */
public final class CommandConfigFile {

    public static final  long    MAX_BYTES       = 1024L * 1024;
    private static final String  API_KEYS        = "apiKeys";
    /** In a Gson message: everything after {@code apiKeys.} / {@code apiKeys[} up to the end may be a key. */
    private static final Pattern API_KEYS_TAIL   = Pattern.compile("apiKeys[.\\[].*$");
    private static final String  KEYS            = "keys";
    /** Anything under {@code keys} (an object or a list by mistake, a stray field): a name there may be a hash or a key. */
    private static final Pattern KEYS_TAIL       = Pattern.compile("(?<![A-Za-z])keys[.\\[].*$");
    /** Deeper YAML is refused before it is walked (JSON: Gson's own nesting limit). */
    static final         int     MAX_YAML_DEPTH  = 64;

    private CommandConfigFile() {
    }

    public static boolean isConfigFileName(String aFileName) {
        return aFileName.endsWith(".json") || aFileName.endsWith(".yml");
    }

    /** {@code dc-docker.json} → {@code dc-docker}. */
    public static String commandName(String aFileName) {
        return aFileName.substring(0, aFileName.lastIndexOf('.'));
    }

    /** The file as a JSON object, or a {@link ConfigProblemException} naming the file. */
    public static JsonObject read(Path aFile) {
        String fileName = aFile.getFileName().toString();
        String text     = readText(aFile, fileName);
        try {
            return fileName.endsWith(".yml") ? parseYaml(text) : parseJson(text);
        } catch (ConfigProblemException e) {
            throw new ConfigProblemException(fileName + ": " + e.getMessage());
        }
    }

    private static String readText(Path aFile, String aFileName) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(aFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            throw new ConfigProblemException(aFileName + ": does not exist");
        } catch (IOException e) {
            throw new ConfigProblemException(aFileName + ": cannot be read (" + e.getClass().getSimpleName() + ")");
        }
        if (attributes.isSymbolicLink()) {
            throw new ConfigProblemException(aFileName + ": is a symbolic link; a config must be a regular file");
        }
        if (!attributes.isRegularFile()) {
            throw new ConfigProblemException(aFileName + ": is not a regular file");
        }
        if (attributes.size() > MAX_BYTES) {
            throw new ConfigProblemException(aFileName + ": is larger than " + MAX_BYTES + " bytes");
        }
        try (FileChannel channel = FileChannel.open(aFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             InputStream in      = Channels.newInputStream(channel)) {
            byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new ConfigProblemException(aFileName + ": is larger than " + MAX_BYTES + " bytes");
            }
            return UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ConfigProblemException(aFileName + ": is not UTF-8 text");
        } catch (IOException e) {
            throw new ConfigProblemException(aFileName + ": cannot be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ── JSON ──────────────────────────────────────────────────────────────

    static JsonObject parseJson(String aText) {
        try (JsonReader reader = new JsonReader(new StringReader(aText))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new ConfigProblemException("not a JSON object");
            }
            JsonObject object = readObject(reader, "");
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new ConfigProblemException("text after the JSON object");
            }
            return object;
        } catch (ConfigProblemException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Gson's message names line/column/path, not values; the path is masked inside apiKeys
            throw new ConfigProblemException("not valid JSON (" + e.getClass().getSimpleName() + maskGsonLocation(location(e)) + ")");
        }
    }

    private static JsonObject readObject(JsonReader aReader, String aPath) throws IOException {
        JsonObject  object = new JsonObject();
        Set<String> names  = new HashSet<>();
        aReader.beginObject();
        while (aReader.hasNext()) {
            String name = aReader.nextName();
            String path = aPath.isEmpty() ? name : aPath + "." + name;
            if (!names.add(name)) {
                throw new ConfigProblemException(maskPath(path) + ": duplicate key");
            }
            object.add(name, readValue(aReader, path));
        }
        aReader.endObject();
        return object;
    }

    private static JsonElement readValue(JsonReader aReader, String aPath) throws IOException {
        return switch (aReader.peek()) {
            case BEGIN_OBJECT -> readObject(aReader, aPath);
            case BEGIN_ARRAY  -> readArray(aReader, aPath);
            case STRING       -> new JsonPrimitive(aReader.nextString());
            case NUMBER       -> new JsonPrimitive(new LazilyParsedNumber(aReader.nextString()));
            case BOOLEAN      -> new JsonPrimitive(aReader.nextBoolean());
            case NULL         -> {
                aReader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new ConfigProblemException(maskPath(aPath) + ": unexpected " + aReader.peek());
        };
    }

    private static JsonArray readArray(JsonReader aReader, String aPath) throws IOException {
        JsonArray array = new JsonArray();
        aReader.beginArray();
        while (aReader.hasNext()) {
            array.add(readValue(aReader, aPath + "[" + array.size() + "]"));
        }
        aReader.endArray();
        return array;
    }

    private static String location(Throwable aError) {
        String  message = String.valueOf(aError.getMessage());
        Matcher matcher = Pattern.compile("line \\d+ column \\d+( path \\S+)?").matcher(message);
        return matcher.find() ? " at " + matcher.group() : "";
    }

    // ── YAML ──────────────────────────────────────────────────────────────

    static JsonObject parseYaml(String aText) {
        Optional<Node> node;
        try {
            node = new Compose(LoadSettings.builder().build()).composeString(aText);
        } catch (RuntimeException e) {
            throw new ConfigProblemException("not valid YAML (" + e.getClass().getSimpleName() + ")");
        } catch (StackOverflowError e) {
            // snakeyaml-engine composes recursively and has no nesting limit of its own; the stack
            // is unwound here and nothing is shared, so the refusal is safe to report
            throw new ConfigProblemException("YAML nested too deeply");
        }
        if (node.isEmpty() || !(node.get() instanceof MappingNode mapping)) {
            throw new ConfigProblemException("not a YAML mapping");
        }
        // An alias makes the node graph shared or cyclic (a cycle overflows the converter's stack);
        // a config needs neither, so any reuse of a node is refused, and so is deep nesting.
        checkTree(mapping, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0);
        List<String> errors = new ArrayList<>();
        JsonObject   object;
        try {
            object = new Yaml2GsonConverter(true).convertToJson(mapping, errors);
        } catch (RuntimeException e) {
            throw new ConfigProblemException("cannot convert YAML (" + e.getClass().getSimpleName() + ")");
        }
        if (!errors.isEmpty()) {
            throw new ConfigProblemException(errors.stream().map(CommandConfigFile::maskYamlError).distinct().toList());
        }
        return object;
    }

    private static void checkTree(Node aNode, Set<Node> aSeen, int aDepth) {
        if (aDepth > MAX_YAML_DEPTH) {
            throw new ConfigProblemException("YAML nested deeper than " + MAX_YAML_DEPTH + " levels");
        }
        if (!aSeen.add(aNode)) {
            throw new ConfigProblemException("YAML anchors and aliases are not supported in a config");
        }
        if (aNode instanceof MappingNode mapping) {
            for (org.snakeyaml.engine.v2.nodes.NodeTuple tuple : mapping.getValue()) {
                checkTree(tuple.getKeyNode(), aSeen, aDepth + 1);
                checkTree(tuple.getValueNode(), aSeen, aDepth + 1);
            }
        } else if (aNode instanceof org.snakeyaml.engine.v2.nodes.SequenceNode sequence) {
            for (Node child : sequence.getValue()) {
                checkTree(child, aSeen, aDepth + 1);
            }
        }
    }

    /**
     * A path under the top-level {@code apiKeys} — whatever its shape, an object or a mistaken
     * array of objects — may hold a key: it is reported as {@code apiKeys.<key>}.
     */
    static String maskPath(String aPath) {
        if (aPath.startsWith(KEYS + ".") || aPath.startsWith(KEYS + "[")) {
            return KEYS + ".<entry>";
        }
        return aPath.startsWith(API_KEYS + ".") || aPath.startsWith(API_KEYS + "[") ? API_KEYS + ".<key>" : aPath;
    }

    /** {@code ... path $.apiKeys.<secret>} → {@code ... path $.apiKeys.<key>}. */
    static String maskGsonLocation(String aLocation) {
        String masked = API_KEYS_TAIL.matcher(aLocation).replaceAll(API_KEYS + ".<key>");
        return KEYS_TAIL.matcher(masked).replaceAll(KEYS + ".<entry>");
    }

    /**
     * A strict-converter error is {@code <path>: <reason>}; under {@code apiKeys.} the path holds a
     * key, which may itself contain {@code ": "} — the whole message is replaced.
     */
    static String maskYamlError(String aError) {
        if (aError.startsWith(KEYS + ".") || aError.startsWith(KEYS + "[")) {
            return KEYS + ".<entry>: a duplicate or non-scalar key";
        }
        return aError.startsWith(API_KEYS + ".") || aError.startsWith(API_KEYS + "[")
                ? API_KEYS + ".<key>: a duplicate or non-scalar key" : aError;
    }
}
