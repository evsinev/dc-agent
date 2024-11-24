package com.payneteasy.dcagent.graalvm;

import com.payneteasy.dcagent.IStartupConfig;
import com.payneteasy.dcagent.core.config.model.*;
import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.config.model.docker.TDockerConfig;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Date;

public class GraalBuildTimeInit {

    static {
        debug("static ...");
        new GraalBuildTimeInit().init();
    }

    public static void main(String[] args) {
        debug("main()");
    }
    private static void debug(String aFormat, Object ... args) {
        System.out.println(new Date() + " " + String.format(aFormat, args));
    }

    private void init() {
        model(TDockerConfig.class);
        model(TDocker.class);
        model(TSaveArtifactConfig.class);
        model(TZipArchiveConfig.class);
        model(TFetchUrlConfig.class);
        model(TJarConfig.class);
        model(TZipDirsConfig.class);

        service(IStartupConfig.class);
        service(IDcAgentControlPlaneRemoteService.class);
    }

    private void service(Class<?> aClass) {
        debug("Service %s", aClass.getSimpleName());
        for (Method method : aClass.getMethods()) {
            debug("  %s()", method.getName());
            model(method.getReturnType());
            for (Parameter parameter : method.getParameters()) {
                model(parameter.getType());
            }
        }
        aClass.getDeclaredMethods();

    }

    private void model(Class<?> aClass) {
        if (aClass.isPrimitive()) {
            return;
        }

        debug("Model %s", aClass.getSimpleName());
        aClass.getFields();
        aClass.getDeclaredFields();
        aClass.getConstructors();

        try {
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);
            unsafe.allocateInstance(aClass);
            lookupMethod(unsafe.getClass(), aClass);
        } catch (Exception e) {
            debug("Cannot init class %s", aClass);
            e.printStackTrace();
        }

    }

    void registerClass(Class<?> aClass) {
        try {
            Class<?> runtimeReflectionClass = Thread.currentThread().getContextClassLoader().loadClass("org.graalvm.nativeimage.hosted.RuntimeReflection");
            Method   method               = runtimeReflectionClass.getMethod("register", Class[].class);
            method.invoke(null, (Object) new Class[] {aClass});
        } catch (Exception e) {
            debug("Cannot register class %s", aClass);
            e.printStackTrace();
        }
    }

    void lookupMethod(Class<?> aUnsafeClass, Class<?> aModelClass) {
        registerClass(aModelClass);
        try {
            Class<?> reflectionUtilClass = Thread.currentThread().getContextClassLoader().loadClass("com.oracle.svm.util.ReflectionUtil");
            Method method = reflectionUtilClass.getMethod("lookupMethod", Class.class, String.class, Class[].class);
            debug("lookupMethod = %s", method);
            method.invoke(null, aUnsafeClass, "allocateInstance", new Class[] {aModelClass});
        } catch (Exception e) {
            debug("Cannot lookupMethod for class %s", aModelClass);
            e.printStackTrace();
        }
    }
}
