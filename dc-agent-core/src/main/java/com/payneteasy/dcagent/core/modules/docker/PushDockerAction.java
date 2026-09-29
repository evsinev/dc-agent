package com.payneteasy.dcagent.core.modules.docker;

import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.config.model.docker.TDockerBoundVariables;
import com.payneteasy.dcagent.core.config.model.docker.security.TSecurityContext;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystem;
import com.payneteasy.dcagent.core.modules.docker.filesystem.IFileSystemFactory;
import com.payneteasy.dcagent.core.modules.docker.preflight.WritePathPreflight;
import com.payneteasy.dcagent.core.modules.docker.resolver.BoundVariablesResolver;
import com.payneteasy.dcagent.core.modules.docker.resolver.ContainerMountsCheck;
import com.payneteasy.dcagent.core.modules.docker.resolver.DockerResolver;
import com.payneteasy.dcagent.core.modules.docker.runtime.ContainerRuntime;
import com.payneteasy.dcagent.core.modules.docker.runtime.PasswdEntryTemplate;
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
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.payneteasy.dcagent.core.util.SafeFiles.deleteFileWithWarning;
import static com.payneteasy.dcagent.core.util.Strings.forLog;
import static java.nio.charset.StandardCharsets.UTF_8;
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
    private final ContainerRuntime      runtime;
    private final Supplier<String>      runtimeVersion;

    /** The container passwd file next to {@code run} (docker runtime with passwdEntry). */
    static final String CONTAINER_PASSWD = "container-passwd";

    public PushDockerAction(String name, TempDir tempDir, ServicesDefinitionDir servicesDefinitionDir, ServicesLogDir servicesLogDir, IActionLogger logger, IFileSystemFactory fileSystemFactory) {
        this(name, tempDir, servicesDefinitionDir, servicesLogDir, logger, fileSystemFactory, ContainerRuntime.PODMAN);
    }

    public PushDockerAction(String name, TempDir tempDir, ServicesDefinitionDir servicesDefinitionDir, ServicesLogDir servicesLogDir, IActionLogger logger, IFileSystemFactory fileSystemFactory, ContainerRuntime runtime) {
        this(name, tempDir, servicesDefinitionDir, servicesLogDir, logger, fileSystemFactory, PushDockerAction::randomWorkDirSuffix, runtime, PushDockerAction::dockerVersion);
    }

    PushDockerAction(String name, TempDir tempDir, ServicesDefinitionDir servicesDefinitionDir, ServicesLogDir servicesLogDir, IActionLogger logger, IFileSystemFactory fileSystemFactory, Supplier<String> workDirSuffix) {
        this(name, tempDir, servicesDefinitionDir, servicesLogDir, logger, fileSystemFactory, workDirSuffix, ContainerRuntime.PODMAN, PushDockerAction::dockerVersion);
    }

    PushDockerAction(String name, TempDir tempDir, ServicesDefinitionDir servicesDefinitionDir, ServicesLogDir servicesLogDir, IActionLogger logger, IFileSystemFactory fileSystemFactory, Supplier<String> workDirSuffix, ContainerRuntime runtime, Supplier<String> runtimeVersion) {
        this.name                  = name;
        this.tempDir               = tempDir;
        this.servicesDefinitionDir = servicesDefinitionDir;
        this.servicesLogDir        = servicesLogDir;
        this.logger                = logger;
        this.fileSystemFactory     = fileSystemFactory;
        this.workDirSuffix         = workDirSuffix;
        this.runtime               = runtime;
        this.runtimeVersion        = runtimeVersion;
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
        paths.put("container passwd", new File(servicesDefinitionDir.getServiceDir(aServiceName), CONTAINER_PASSWD));
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
        Path root = new WritePathPreflight(Map.of()).ensureTrustedDirectory("TEMP_DIR", withoutDotComponents(tempDir.getTempDir())).toPath();
        for (int attempt = 0; attempt < WORK_DIR_ATTEMPTS; attempt++) {
            Path dir = root.resolve("docker-" + safeName(name) + "-" + workDirSuffix.get()).normalize();
            if (!dir.startsWith(root) || dir.equals(root)) {
                throw new IllegalStateException("Work dir " + dir + " is not inside " + root);
            }
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

    /**
     * passwdEntry depends on what {@code docker} really is: podman's own --passwd-entry is unknown to
     * docker (the container would not start). Checked against {@code docker --version} before
     * anything changes, for CHECK and PUSH alike; configs without passwdEntry are not affected.
     */
    private void checkRuntime(TDocker aDocker) {
        if (aDocker.getSecurityContext() == null || aDocker.getSecurityContext().getPasswdEntry() == null) {
            return;
        }
        String version = runtimeVersion.get();
        if (!runtime.matchesVersionOutput(version)) {
            ContainerRuntime other = runtime == ContainerRuntime.PODMAN ? ContainerRuntime.DOCKER : ContainerRuntime.PODMAN;
            throw new IllegalStateException("securityContext.passwdEntry: CONTAINER_RUNTIME is " + runtime.parameterValue()
                    + " but 'docker --version' says '" + version.trim() + "'; set CONTAINER_RUNTIME=" + other.parameterValue() + " for this agent");
        }
        logger.info("\uD83D\uDC64  passwdEntry via {} ({})", runtime.parameterValue(), version.trim()); // 👤
    }

    /**
     * Before anything changes (volumes, run): the service directory must be trusted when
     * container-passwd will be written or deleted — a refusal later would leave a half-updated service.
     */
    private void checkServiceDirectoryForPasswd(TDocker aDocker) {
        File file = containerPasswdFile(aDocker.getName());
        if (containerPasswdNeeded(aDocker) || Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            new WritePathPreflight(Map.of()).checkServiceDirectory("service dir", file.getParentFile(), false);
        }
        if (containerPasswdNeeded(aDocker) && Files.isSymbolicLink(file.toPath())) {
            throw new IllegalStateException(file.getAbsolutePath() + " is a symbolic link; the agent writes this file itself — remove the link");
        }
    }

    private File containerPasswdFile(String aServiceName) {
        Path root = withoutDotComponents(servicesDefinitionDir.getServiceDir("x").getParentFile()).toPath().normalize();
        Path file = root.resolve(aServiceName).resolve(CONTAINER_PASSWD).normalize();
        if (!file.startsWith(root) || !file.getParent().getParent().equals(root)) {
            throw new IllegalStateException("Service dir of '" + aServiceName + "' is not directly inside " + root);
        }
        return file.toFile();
    }

    private boolean containerPasswdNeeded(TDocker aDocker) {
        return runtime == ContainerRuntime.DOCKER && aDocker.getSecurityContext() != null && aDocker.getSecurityContext().getPasswdEntry() != null;
    }

    /**
     * docker runtime with passwdEntry: {@code container-passwd} (root + the entry, never the host's
     * passwd) next to {@code run}, mounted over /etc/passwd. Removed when not needed any more. The
     * service directory is trusted like {@code run}: checked before a write and a delete.
     */
    private void writeContainerPasswd(TDocker aDocker, IFileSystem aFileSystem) {
        File    file   = containerPasswdFile(aDocker.getName());
        boolean needed = containerPasswdNeeded(aDocker);
        if (!needed && !Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        new WritePathPreflight(Map.of()).checkServiceDirectory("service dir", file.getParentFile(), false);
        if (!needed) {
            aFileSystem.deleteFileIfExists(file);
            return;
        }
        TSecurityContext context = aDocker.getSecurityContext();
        String entry = PasswdEntryTemplate.render(context.getPasswdEntry(), context.getRunAsUser(), context.getRunAsGroup());
        String text  = "root:x:0:0:root:/root:/sbin/nologin\n" + entry + "\n";
        aFileSystem.writeFileWithMode(file, text.getBytes(UTF_8), "0644");
    }

    static String dockerVersion() {
        File output = null;
        try {
            // Files.createTempFile: 0600, not readable by others (unlike File.createTempFile)
            output = Files.createTempFile("docker-version", ".txt").toFile();
            // output goes to a file: a wrapper that hangs with stdout open must not block past the timeout
            Process process = new ProcessBuilder("docker", "--version").redirectErrorStream(true).redirectOutput(output).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("docker --version did not finish in 30s");
            }
            return Files.readString(output.toPath(), UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot run 'docker --version' to check CONTAINER_RUNTIME: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running docker --version", e);
        } finally {
            if (output != null) {
                deleteFileWithWarning(output, "docker --version output");
            }
        }
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
            TDocker               unresolved   = yamlParser.parseTextStrict(yaml, TDocker.class, "dc-docker.yml");
            ContainerMountsCheck.checkName(unresolved);
            checkRuntime(unresolved);
            checkServiceDirectoryForPasswd(unresolved);
            TDocker               docker       = resolver.resolve(unresolved, dir, fileSystem, logger, new WritePathPreflight(agentWritePaths(unresolved.getName(), dir)));

            ServiceDefinitionCreator definitionCreator = new ServiceDefinitionCreator(
                    servicesDefinitionDir, fileSystem
            );

            File containerPasswd = containerPasswdFile(docker.getName());
            definitionCreator.createService(
                      docker.getName()
                    , DockerRunFileBuilder.createRunFileText(docker, servicesDefinitionDir.getServiceEnvDir(docker.getName()).getAbsolutePath(), runtime, containerPasswd)
                    , DockerLogFileBuilder.createLogFileText(servicesLogDir, docker)
                    , docker.getOwner()
            );
            writeContainerPasswd(docker, fileSystem);
        } finally {
            // Remove the extracted working dir (default on); disable with DOCKER_DELETE_TEMP_DIR=false
            // to inspect the files. Sentinel-guarded to the (physical) temp root so only this sub-dir is deleted.
            if (tempDir.isDeleteAfterExtract()) {
                new DeleteDirRecursively(dir.getParentFile()).deleteDirIfExists(dir);
            }
        }
    }
}
