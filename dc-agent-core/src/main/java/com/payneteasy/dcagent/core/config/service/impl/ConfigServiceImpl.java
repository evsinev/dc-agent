package com.payneteasy.dcagent.core.config.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.payneteasy.dcagent.core.config.CommandNames;
import com.payneteasy.dcagent.core.config.model.*;
import com.payneteasy.dcagent.core.config.model.docker.TDockerConfig;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.config.validation.CommandConfigFile;
import com.payneteasy.dcagent.core.config.validation.CommandConfigValidator;
import com.payneteasy.dcagent.core.config.validation.ConfigProblemException;
import com.payneteasy.dcagent.core.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads a command config on every request: {@code <name>.json} or {@code <name>.yml} in CONFIG_DIR,
 * read by {@link CommandConfigFile} and checked by {@link CommandConfigValidator} — the same rules
 * the agent applied to the directory at start, because a config may be added or changed while it
 * runs. A refused config is a {@link ConfigProblemException} (the task endpoints turn it into the
 * uniform 401); its problems go to the log here.
 */
public class ConfigServiceImpl implements IConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigServiceImpl.class);

    private final File                   configDir;
    private final Gson                   gson;
    private final CommandConfigValidator validator;

    public ConfigServiceImpl(File aConfigDir, Gson aGson, CommandConfigValidator aValidator) {
        configDir = aConfigDir;
        gson      = aGson;
        validator = aValidator;
    }

    @Override
    public TZipArchiveConfig getZipArchiveConfig(String aName) {
        return loadConfig(TZipArchiveConfig.class, aName);
    }

    @Override
    public TFetchUrlConfig getFetchUrlConfig() {
        return loadConfig(TFetchUrlConfig.class, CommandConfigValidator.FETCH_URL_NAME);
    }

    private <T> T loadConfig(Class<T> aClass, String aName) {
        if (!CommandNames.isValid(aName)) {
            // the task endpoints check the name first; this keeps a path out of CONFIG_DIR for any caller
            throw new IllegalArgumentException("Invalid command name");
        }
        Path    json       = configDir.toPath().resolve(aName + ".json");
        Path    yaml       = configDir.toPath().resolve(aName + ".yml");
        boolean jsonExists = Files.exists(json, LinkOption.NOFOLLOW_LINKS);
        boolean yamlExists = Files.exists(yaml, LinkOption.NOFOLLOW_LINKS);

        if (!jsonExists && !yamlExists) {
            throw new IllegalStateException("No config " + aName + ".json or " + aName + ".yml in CONFIG_DIR");
        }
        if (jsonExists && yamlExists) {
            throw refused(aName, List.of(aName + ".json and " + aName + ".yml both exist; keep one"));
        }

        JsonObject object;
        try {
            object = CommandConfigFile.read(jsonExists ? json : yaml);
        } catch (ConfigProblemException e) {
            throw refused(aName, e.getProblems());
        }
        List<String> problems = validator.validate(aName, object);
        if (!problems.isEmpty()) {
            throw refused(aName, problems);
        }
        return gson.fromJson(object, aClass);
    }

    private static ConfigProblemException refused(String aName, List<String> aProblems) {
        LOG.warn("Command config {} is refused: {}", Strings.forLog(aName), String.join("; ", aProblems));
        return new ConfigProblemException(aProblems);
    }

    @Override
    public TSaveArtifactConfig getSaveArtifactConfig(String aName) {
        return loadConfig(TSaveArtifactConfig.class, aName);
    }

    @Override
    public TJarConfig getJarConfig(String aName) {
        return loadConfig(TJarConfig.class, aName);
    }

    @Override
    public TDockerConfig getServiceConfig(String aName) {
        return loadConfig(TDockerConfig.class, aName);
    }

    @Override
    public TZipDirsConfig getZipDirsConfig(String aName) {
        return loadConfig(TZipDirsConfig.class, aName);
    }

    @Override
    public TZipArchiveVersionConfig getZipArchiveVersionConfig(String aName) {
        return loadConfig(TZipArchiveVersionConfig.class, aName);
    }
}
