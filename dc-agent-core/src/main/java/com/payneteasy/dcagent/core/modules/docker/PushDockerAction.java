package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystem;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystemFactory;
import com.payneteasy.dcagent.core.modules.docker.resolver.BoundVariablesResolver;
import com.payneteasy.dcagent.core.modules.docker.resolver.DockerResolver;
import com.payneteasy.dcagent.core.modules.zipachive.ZipFileExtractor;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

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


    public void pushService(File aFile) {
        File dir = new File(tempDir.getTempDir(), "docker-" + name + "-" +System.currentTimeMillis());
        try {
            zipFileExtractor.extractZip(aFile, dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot extract zip file", e);
        }

        IFileSystem fileSystem = fileSystemFactory.createFileSystem(logger);

        File    dcDockerFile  = new File(dir, "dc-docker.yml");
        TDocker tempDocker    = yamlParser.parseFile(dcDockerFile, TDocker.class);
        String  yaml          = handlebars.processTemplate(dcDockerFile, boundVariablesResolver.mergeVariables(tempDocker.getBoundVariables(), tempDocker.getBoundVariablesMap()));
        TDocker unresolved    = yamlParser.parseText(yaml, TDocker.class);
        TDocker docker        = resolver.resolve(unresolved, dir, fileSystem, logger);

        ServiceDefinitionCreator definitionCreator = new ServiceDefinitionCreator(
                servicesDefinitionDir, fileSystem
        );

        definitionCreator.createService(
                  docker.getName()
                , DockerRunFileBuilder.createRunFileText(docker, servicesDefinitionDir.getServiceEnvDir(docker.getName()).getAbsolutePath())
                , DockerLogFileBuilder.createLogFileText(servicesLogDir, docker)
                , docker.getOwner()
        );

    }
}
