package com.payneteasy.dcagent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classes the agent reads or writes with Gson must be registered for the native image whole —
 * fields, constructors and unsafe allocation (Gson creates a class without a no-arg constructor
 * through Unsafe). The tracing agent only records the paths a run took; a model it did not see
 * would silently lose its fields in the binary. So the list is computed from the code here and the
 * metadata file must match it exactly.
 * <p>
 * To regenerate the file: {@code ./mvnw -pl dc-agent-app test -Dtest=NativeMetadataTest -Dnative.metadata.write=true}.
 */
public class NativeMetadataTest {

    static final String FILE = "src/main/resources/META-INF/native-image/com.payneteasy/dc-agent-models/reachability-metadata.json";

    /** Packages whose classes are Gson models (recursively). */
    private static final String[] PACKAGES = {
            "com.payneteasy.dcagent.core.config.model",
            "com.payneteasy.dcagent.core.remote.agent.controlplane.messages",
            "com.payneteasy.dcagent.core.remote.agent.controlplane.model",
            // /app-status/* answers with these through its own Gson
            "com.payneteasy.jetty.util.appstatus.messages",
            "com.payneteasy.apiservlet.problem",
    };

    /** Single classes serialized by the agent. */
    private static final Class<?>[] CLASSES = {
            com.payneteasy.dcagent.jetty.ProblemDetail.class,
            com.payneteasy.apiservlet.VoidRequest.class,
    };

    /** Interfaces implemented by a JDK dynamic proxy (startup-parameters builds one for the config). */
    private static final String[] PROXIES = {
            IStartupConfig.class.getName(),
    };

    @Test
    public void every_gson_model_is_registered_whole() throws Exception {
        String expected = render(expectedTypes());
        Path   file     = Path.of(FILE);

        if (Boolean.getBoolean("native.metadata.write")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, expected, UTF_8);
        }

        assertThat(file).as("run with -Dnative.metadata.write=true to create it").exists();
        assertThat(Files.readString(file, UTF_8))
                .as("native metadata out of date: run NativeMetadataTest with -Dnative.metadata.write=true")
                .isEqualTo(expected);
    }

    @Test
    public void the_list_covers_known_models_and_adapters() throws Exception {
        Set<String> types = expectedTypes();

        assertThat(types).contains(
                "com.payneteasy.dcagent.core.remote.agent.controlplane.messages.CommandWarRequest",
                "com.payneteasy.dcagent.core.config.model.TZipArchiveVersionConfig",
                "com.payneteasy.dcagent.core.config.model.docker.TDocker",
                "com.payneteasy.jetty.util.appstatus.messages.AppStatusResponse",
                "com.payneteasy.dcagent.jetty.ProblemDetail");
        // a @JsonAdapter class outside the scanned packages is found through the annotation
        assertThat(types).anyMatch(name -> name.endsWith("StrictBooleanAdapter"));
    }

    static Set<String> expectedTypes() throws IOException {
        Set<Class<?>> classes = new java.util.LinkedHashSet<>();
        for (String pkg : PACKAGES) {
            for (String name : classNames(pkg)) {
                try {
                    classes.add(Class.forName(name, false, NativeMetadataTest.class.getClassLoader()));
                } catch (ClassNotFoundException | LinkageError e) {
                    throw new IllegalStateException("Cannot load " + name, e);
                }
            }
        }
        classes.addAll(List.of(CLASSES));

        Set<String> types = new TreeSet<>();
        for (Class<?> type : classes) {
            if (type.isAnnotation() || type.isAnonymousClass() || type.isSynthetic()
                    || Throwable.class.isAssignableFrom(type)
                    // Lombok builders: code only, never (de)serialized
                    || type.getEnclosingClass() != null && type.getSimpleName().endsWith("Builder")) {
                continue;
            }
            types.add(type.getName());
            adapters(type, types);
        }
        return types;
    }

    /** {@code @JsonAdapter} on the class or its fields: Gson instantiates that adapter reflectively. */
    private static void adapters(Class<?> aType, Set<String> aTypes) {
        JsonAdapter onClass = aType.getAnnotation(JsonAdapter.class);
        if (onClass != null) {
            aTypes.add(onClass.value().getName());
        }
        for (Field field : aType.getDeclaredFields()) {
            JsonAdapter onField = field.getAnnotation(JsonAdapter.class);
            if (onField != null) {
                aTypes.add(onField.value().getName());
            }
        }
    }

    private static String render(Set<String> aTypes) throws IOException {
        JsonArray reflection = new JsonArray();
        for (String name : aTypes) {
            Class<?>   type  = load(name);
            JsonObject entry = new JsonObject();
            entry.addProperty("type", name);
            entry.addProperty("allDeclaredFields", true);
            entry.addProperty("allDeclaredConstructors", true);
            if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers()) && !type.isEnum()) {
                entry.addProperty("unsafeAllocated", true);
            }
            reflection.add(entry);
        }
        for (String proxy : PROXIES) {
            JsonObject entry = new JsonObject();
            JsonObject type  = new JsonObject();
            JsonArray  list  = new JsonArray();
            list.add(proxy);
            type.add("proxy", list);
            entry.add("type", type);
            reflection.add(entry);

            JsonObject methods = new JsonObject();
            methods.addProperty("type", proxy);
            methods.addProperty("allPublicMethods", true);
            reflection.add(methods);
        }
        JsonObject root = new JsonObject();
        root.addProperty("comment", "Generated by NativeMetadataTest from the code: every Gson model whole. Do not edit.");
        root.add("reflection", reflection);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson((JsonElement) root) + "\n";
    }

    private static Class<?> load(String aName) {
        try {
            return Class.forName(aName, false, NativeMetadataTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every class name under the package (recursively), from directories and jars on the class path. */
    private static List<String> classNames(String aPackage) throws IOException {
        String       path  = aPackage.replace('.', '/');
        List<String> names = new ArrayList<>();
        Enumeration<URL> roots = NativeMetadataTest.class.getClassLoader().getResources(path);
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            if ("file".equals(root.getProtocol())) {
                File dir = new File(URLDecoder.decode(root.getPath(), UTF_8));
                try (var files = Files.walk(dir.toPath())) {
                    files.filter(file -> file.toString().endsWith(".class"))
                         .forEach(file -> names.add(aPackage + "." + dir.toPath().relativize(file).toString()
                                 .replace(File.separatorChar, '.').replaceAll("\\.class$", "")));
                }
            } else if ("jar".equals(root.getProtocol())) {
                JarURLConnection connection = (JarURLConnection) root.openConnection();
                connection.setUseCaches(false);
                try (JarFile jar = connection.getJarFile()) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String entry = entries.nextElement().getName();
                        if (entry.startsWith(path + "/") && entry.endsWith(".class")) {
                            names.add(entry.replace('/', '.').replaceAll("\\.class$", ""));
                        }
                    }
                }
            }
        }
        names.removeIf(name -> name.endsWith("package-info") || name.endsWith("module-info"));
        return names;
    }
}
