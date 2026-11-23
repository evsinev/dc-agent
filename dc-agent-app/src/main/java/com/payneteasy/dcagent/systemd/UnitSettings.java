package com.payneteasy.dcagent.systemd;

import com.payneteasy.dcagent.IStartupConfig;
import com.payneteasy.dcagent.core.config.validation.CommandPathPolicy;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Everything a generated drop-in depends on besides the configs: the agent's settings that decide which
 * paths it uses (written into the drop-in as {@code Environment=}, so the service runs with exactly them)
 * and the generator's own options (written into its header). {@code --check} reads both back.
 */
record UnitSettings(String commandDirs, String servicesDir, String servicesDefinitionDir, String servicesLogDir,
                    String idleTimeout, String listen, String slice) {

    static final String OPTIONS_PREFIX = "# options:";

    /** The settings of the agent at hand (its environment), with the generator's options. */
    static UnitSettings of(IStartupConfig aConfig, String aCommandDirs, String aListen, String aSlice) {
        String commandDirs = aCommandDirs != null ? aCommandDirs : aConfig.getCommandDirs();
        return new UnitSettings(canonical(commandDirs), aConfig.getServicesDir().getPath(),
                aConfig.getServicesDefinitionDir().getPath(), aConfig.getServicesLogDir().getPath(),
                aConfig.getJettyIdleTimeout(), aListen != null ? aListen : "127.0.0.1:" + aConfig.getJettyPort(), aSlice);
    }

    /** COMMAND_DIRS as the agent parses it, joined by "," — no spaces for systemd to split on. */
    static String canonical(String aCommandDirs) {
        return CommandPathPolicy.parseCommandDirs(aCommandDirs).stream().map(Path::toString).collect(Collectors.joining(","));
    }

    /** The service environment the drop-in sets: every non-secret parameter the generation relied on. */
    Map<String, String> environment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("WEB_SERVER_INHERITED", "true");
        env.put("CONTROL_PLANE_ENABLED", "false");
        env.put("COMMAND_DIRS", commandDirs);
        env.put("SERVICES_DIR", servicesDir);
        env.put("SERVICES_DEFINITION_DIR", servicesDefinitionDir);
        env.put("SERVICES_LOG_DIR", servicesLogDir);
        env.put("WEB_SERVER_IDLE_TIMEOUT", idleTimeout);
        return env;
    }

    /** Variables /etc/dc-agent/env may hold: secrets only (everything else is set by the drop-in). */
    static final List<String> SECRET_VARIABLES = List.of("APP_STATUS_TOKEN");

    /** What would let systemd or a shell reinterpret a value: refused before anything is printed. */
    List<String> problems() {
        List<String> problems = new java.util.ArrayList<>();
        for (Map.Entry<String, String> variable : environment().entrySet()) {
            String value = variable.getValue();
            boolean ok = switch (variable.getKey()) {
                case "COMMAND_DIRS" -> java.util.Arrays.stream(value.split(",")).allMatch(d -> SandboxPlanner.SAFE_PATH.matcher(d).matches());
                case "SERVICES_DIR", "SERVICES_DEFINITION_DIR", "SERVICES_LOG_DIR" -> SandboxPlanner.SAFE_PATH.matcher(value).matches();
                case "WEB_SERVER_IDLE_TIMEOUT" -> value.matches("[0-9A-Za-z.]+");
                default -> true;
            };
            if (!ok) {
                problems.add(variable.getKey() + " '" + value + "' has characters the unit and the script cannot take");
            }
        }
        return problems;
    }

    String optionsLine() {
        return OPTIONS_PREFIX + " --listen " + listen + (slice == null ? "" : " --slice " + slice);
    }

    /** The settings recorded in an installed drop-in; null when it is not one of ours. */
    static UnitSettings parse(String aDropIn) {
        Map<String, String> env     = new LinkedHashMap<>();
        String              listen  = null;
        String              slice   = null;
        boolean             options = false;
        for (String line : aDropIn.split("\n")) {
            if (line.startsWith(OPTIONS_PREFIX)) {
                options = true;
                String[] words = line.substring(OPTIONS_PREFIX.length()).trim().split("\\s+");
                for (int i = 0; i + 1 < words.length; i += 2) {
                    if ("--listen".equals(words[i])) {
                        listen = words[i + 1];
                    } else if ("--slice".equals(words[i])) {
                        slice = words[i + 1];
                    }
                }
            } else if (line.startsWith("Environment=")) {
                String assignment = line.substring("Environment=".length());
                int    equals     = assignment.indexOf('=');
                if (equals > 0) {
                    env.put(assignment.substring(0, equals), assignment.substring(equals + 1));
                }
            }
        }
        if (!options || listen == null || !env.containsKey("COMMAND_DIRS")) {
            return null;
        }
        return new UnitSettings(env.get("COMMAND_DIRS"), env.get("SERVICES_DIR"), env.get("SERVICES_DEFINITION_DIR"),
                env.get("SERVICES_LOG_DIR"), env.get("WEB_SERVER_IDLE_TIMEOUT"), listen, slice);
    }

    /** The agent's settings with these values in place of its own (validation as the service will run). */
    IStartupConfig apply(IStartupConfig aConfig) {
        return (IStartupConfig) Proxy.newProxyInstance(UnitSettings.class.getClassLoader(), new Class[]{IStartupConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCommandDirs"           -> commandDirs;
                    case "getServicesDir"           -> new File(servicesDir);
                    case "getServicesDefinitionDir" -> new File(servicesDefinitionDir);
                    case "getServicesLogDir"        -> new File(servicesLogDir);
                    case "getJettyIdleTimeout"      -> idleTimeout;
                    case "isControlPlaneEnabled"    -> false;
                    case "isJettyInherited"         -> true;
                    default                         -> method.invoke(aConfig, args);
                });
    }
}
