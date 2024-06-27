package com.payneteasy.dcagent.graal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.payneteasy.dcagent.graal.ReflectionConfigItem.gsonDataClass;
import static com.payneteasy.dcagent.graal.ReflectionConfigItem.toReflectionEnum;
import static java.lang.Thread.currentThread;

public class GenerateGraalReflectionMain {

    private final ClassLoader classLoader = currentThread().getContextClassLoader();
    private final List<String> packages    = new ArrayList<>();

    public static void main(String[] args) {
        new GenerateGraalReflectionMain().run();
    }

    private void run() {

        packages.add("com.payneteasy.dcagent.core.config.model");
        packages.add("com.payneteasy.dcagent.core.remote.agent.controlplane");

        Reflections reflections = new Reflections();

        List<ReflectionConfigItem> items = reflections.getAllTypes().stream()
                .filter(this::filterPackages)
                .filter(this::filterClassName)
                .map(this::loadClass)
                .filter(this::filterClass)
                .map(this::toReflectionItem)
                .collect(Collectors.toList());

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println(gson.toJson(items));

    }

    private ReflectionConfigItem toReflectionItem(Class<?> aClass) {
        if (aClass.isEnum()) {
            return toReflectionEnum(aClass);
        }

        return gsonDataClass(aClass);
    }

    private boolean filterClass(Class<?> aClass) {
        return !aClass.isInterface();
    }

    private boolean filterClassName(String aName) {
        boolean isBuilder = aName.contains("$") && aName.endsWith("Builder");
        return !isBuilder;
    }

    private boolean filterPackages(String aName) {
        for (String packageName : packages) {
            if (aName.startsWith(packageName)) {
                return true;
            }
        }
        return false;
    }

    private Class<?> loadClass(String name) {
        try {
            return classLoader.loadClass(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot load class " + name, e);
        }
    }
}
