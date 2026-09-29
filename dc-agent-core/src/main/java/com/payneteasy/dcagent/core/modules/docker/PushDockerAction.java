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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.payneteasy.dcagent.core.util.SafeFiles.deleteFileWithWarning;
import static com.payneteasy.dcagent.core.util.Strings.forLog;
import static com.payneteasy.dcagent.core.util.Streams.writeToTempFile;

public class PushDockerAction {

    private static final Logger LOG = LoggerFactory.getLogger(PushDockerAction.class);

    private static final FileAttribute<Set<PosixFilePermission>> PRIVATE_DIRECTORY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final int          WORK_DIR_ATTEMPTS = 10;
    private static final SecureRandom RANDOM            = new SecureRandom();

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
    private final Supplier<String>      workDirSuffix;

    public PushDockerAction(String name, TempDir tempDir, ServicesDefinitionDir servicesDefinitionDir, ServicesLogDir servicesLogDir, IActionLogger logger, IFileSystemFactory fileSystemFactory) {
        this(name, tempDir, servicesDefinitionDir, servicesLogDir, logger, fileSystemFactory, PushDockerAction::randomWorkDirSuffix);
    }

    PushDockerAction(String name, TempDir tempDir, ServicesDefinitionDir servicesDefinitionDir, ServicesLogDir servicesLogDir, IActionLogger logger, IFileSystemFactory fileSystemFactory, Supplier<String> workDirSuffix) {
        this.name                  = name;
        this.tempDir               = tempDir;
        this.servicesDefinitionDir = servicesDefinitionDir;
        this.servicesLogDir        = servicesLogDir;
        this.logger                = logger;
        this.fileSystemFactory     = fileSystemFactory;
        this.workDirSuffix         = workDirSuffix;
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

    /**
     * The task is extracted into a fresh directory only root (the agent) can enter: created
     * exclusively ({@code rwx------}) in a temp root that nobody else can change. A predictable
     * name reused from a directory someone prepared in {@code /tmp} would let a local user swap
     * the task's files (dc-docker.yml) or steer the agent's writes and deletes through links.
     */
    private File createWorkDir() {
        File root = new WritePathPreflight(Map.of()).ensureTrustedDirectory("TEMP_DIR", withoutDotComponents(tempDir.getTempDir()));
        for (int attempt = 0; attempt < WORK_DIR_ATTEMPTS; attempt++) {
            Path dir = root.toPath().resolve("docker-" + safeName(name) + "-" + workDirSuffix.get());
            try {
                return Files.createDirectory(dir, PRIVATE_DIRECTORY).toFile();
            } catch (FileAlreadyExistsException e) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("Work dir {} already exists, not reusing it", forLog(dir.toString()));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create work dir " + dir, e);
            }
        }
        throw new IllegalStateException("Cannot create a new work dir in " + root + " after " + WORK_DIR_ATTEMPTS + " attempts");
    }

    /** The name comes from the request URL: only a plain file-name part of it goes into the path. */
    static String safeName(String aName) {
        return aName.replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "__");
    }

    private static String randomWorkDirSuffix() {
        byte[] random = new byte[4];
        RANDOM.nextBytes(random);
        return System.currentTimeMillis() + "-" + HexFormat.of().formatHex(random);
    }

    public void pushService(File aFile) {
        File dir = createWorkDir();
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
            // to inspect the files. Sentinel-guarded to the (physical) temp root so only this sub-dir is deleted.
            if (tempDir.isDeleteAfterExtract()) {
                new DeleteDirRecursively(dir.getParentFile()).deleteDirIfExists(dir);
            }
        }
    }
}
