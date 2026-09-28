package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.config.model.docker.TDockerBoundVariables;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystem;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystemFactory;
import com.payneteasy.dcagent.core.modules.docker.preflight.WritePathPreflight;
import com.payneteasy.dcagent.core.modules.docker.resolver.BoundVariablesResolver;
import com.payneteasy.dcagent.core.modules.docker.resolver.DockerResolver;
import com.payneteasy.dcagent.core.modules.zipachive.ZipFileExtractor;
import com.payneteasy.dcagent.core.util.DeleteDirRecursively;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.payneteasy.dcagent.core.util.SafeFiles.deleteFileWithWarning;
import static com.payneteasy.dcagent.core.util.Streams.writeToTempFile;

public class PushDockerAction {

    private static final Logger LOG = LoggerFactory.getLogger(PushDockerAction.class);

    private final ZipFileExtractor       zipFileExtractor       = new ZipFileExtractor();
    private final YamlParser             yamlParser             = new YamlParser();
    private final HandlebarProcessor     handlebars             = new HandlebarProcessor();
    private final DockerResolver         resolver               = new DockerResolver();
    private final BoundVariablesResolver boundVariablesResolver = new BoundVariablesResolver();


    private final String                name;
    private final TempDir               tempDir;
    private final ServicesDefinitionDir servicesDefinitionDir;
    private final ServicesLogDir        servicesLogDir;
    private final IActionLogger         logger;
    private final IFileSystemFactory    fileSystemFactory;

    public PushDockerAction(String name, TempDir tempDir, ServicesDefinitionDir servicesDefinitionDir, ServicesLogDir servicesLogDir, IActionLogger logger, IFileSystemFactory fileSystemFactory) {
        this.name                  = name;
        this.tempDir               = tempDir;
        this.servicesDefinitionDir = servicesDefinitionDir;
        this.servicesLogDir        = servicesLogDir;
        this.logger                = logger;
        this.fileSystemFactory     = fileSystemFactory;
    }

    public void pushService(InputStream aInputStream) {
        File tempFile = writeToTempFile(aInputStream, "service-" + name, ".zip");
        try {
            pushService(tempFile);
        } finally {
            deleteFileWithWarning(tempFile, "Temp file");
        }
    }


    /**
     * Files and directories the agent writes besides the volumes: the daemontools service (see
     * ServiceDefinitionCreator) and the extracted task, which is deleted recursively afterwards.
     */
    private Map<String, File> agentWritePaths(String aServiceName, File aExtractedDir) {
        Map<String, File> paths = new LinkedHashMap<>();
        paths.put("extracted task" , aExtractedDir);
        paths.put("service dir"    , servicesDefinitionDir.getServiceDir(aServiceName));
        paths.put("service env dir", servicesDefinitionDir.getServiceEnvDir(aServiceName));
        paths.put("service run"    , servicesDefinitionDir.getServiceRunFile(aServiceName));
        paths.put("service log dir", servicesDefinitionDir.getServiceLogDir(aServiceName));
        paths.put("service log run", servicesDefinitionDir.getServiceLogFile(aServiceName));
        paths.put("log dir"        , servicesLogDir.getServiceLogDir(aServiceName));
        // The agent's own directories may be configured relative (tests, local runs). Only '.' is
        // dropped: '..' after a link is not its lexical parent, so WritePathPreflight rejects it.
        paths.replaceAll((label, file) -> withoutDotComponents(file));
        return paths;
    }

    private static File withoutDotComponents(File aFile) {
        Path absolute = aFile.toPath().toAbsolutePath();
        Path result   = absolute.getRoot();
        if (result == null) {
            return absolute.toFile();
        }
        for (Path name : absolute) {
            if (!".".equals(name.toString())) {
                result = result.resolve(name);
            }
        }
        return result.toFile();
    }

    public void pushService(File aFile) {
        File dir = new File(tempDir.getTempDir(), "docker-" + name + "-" +System.currentTimeMillis());
        try {
            try {
                zipFileExtractor.extractZip(aFile, dir);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot extract zip file", e);
            }

            IFileSystem fileSystem = fileSystemFactory.createFileSystem(logger);

            File                  dcDockerFile = new File(dir, "dc-docker.yml");
            TDockerBoundVariables variables    = yamlParser.parseFile(dcDockerFile, TDockerBoundVariables.class);
            String                yaml         = handlebars.processTemplate(dcDockerFile, boundVariablesResolver.mergeVariables(variables.getBoundVariables(), variables.getBoundVariablesMap()));
            TDocker               unresolved   = yamlParser.parseText(yaml, TDocker.class);
            TDocker               docker       = resolver.resolve(unresolved, dir, fileSystem, logger, new WritePathPreflight(agentWritePaths(unresolved.getName(), dir)));

            ServiceDefinitionCreator definitionCreator = new ServiceDefinitionCreator(
                    servicesDefinitionDir, fileSystem
            );

            definitionCreator.createService(
                      docker.getName()
                    , DockerRunFileBuilder.createRunFileText(docker, servicesDefinitionDir.getServiceEnvDir(docker.getName()).getAbsolutePath())
                    , DockerLogFileBuilder.createLogFileText(servicesLogDir, docker)
                    , docker.getOwner()
            );
        } finally {
            // Remove the extracted working dir (default on); disable with DOCKER_DELETE_TEMP_DIR=false
            // to inspect the files. Sentinel-guarded to the temp root so only this sub-dir is deleted.
            if (tempDir.isDeleteAfterExtract()) {
                new DeleteDirRecursively(tempDir.getTempDir()).deleteDirIfExists(dir);
            }
        }
    }
}
