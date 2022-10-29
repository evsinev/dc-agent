package com.payneteasy.dcagent.modules.docker;

import com.payneteasy.dcagent.config.model.docker.TDocker;
import com.payneteasy.dcagent.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.modules.docker.resolver.DockerResolver;
import com.payneteasy.dcagent.modules.zipachive.ZipFileExtractor;
import com.payneteasy.dcagent.yaml2json.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import static com.payneteasy.dcagent.util.SafeFiles.deleteFileWithWarning;
import static com.payneteasy.dcagent.util.Streams.writeToTempFile;

public class PushDockerAction {

    private static final Logger LOG = LoggerFactory.getLogger( PushDockerAction.class );

    private final String name;

    private final ZipFileExtractor      zipFileExtractor           = new ZipFileExtractor();
    private final YamlParser            yamlParser                 = new YamlParser();
    private final HandlebarProcessor    handlebars                 = new HandlebarProcessor();
    private final DockerResolver        resolver                   = new DockerResolver();

    private final TempDir               tempDir;

    public PushDockerAction(String name, TempDir tempDir) {
        this.name    = name;
        this.tempDir = tempDir;
    }

    public void pushService(InputStream aInputStream) {
        File tempFile = writeToTempFile(aInputStream, "service-" + name, ".zip");
        try {
            pushService(tempFile);
        } finally {
            deleteFileWithWarning(tempFile, "Temp file");
        }
    }


    void pushService(File aFile) {
        File dir = new File(tempDir.getTempDir(), "service-" + name + System.currentTimeMillis());
        try {
            zipFileExtractor.extractZip(aFile, dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot extract zip file", e);
        }

        File    dcDockerFile = new File(dir, "dc-docker.yml");
        TDocker tempDocker   = yamlParser.parseFile(dcDockerFile, TDocker.class);
        String  yaml         = handlebars.processTemplate(dcDockerFile, tempDocker.getBoundVariables());
        TDocker unresolved   = yamlParser.parseText(yaml, TDocker.class);
        TDocker docker       = resolver.resolve(unresolved, dir);

        DockerRunFileBuilder runFileBuilder = new DockerRunFileBuilder();
        runFileBuilder.createRunFile(docker);
        System.out.println("runFileBuilder = \n" + runFileBuilder.buildText());
    }
}
