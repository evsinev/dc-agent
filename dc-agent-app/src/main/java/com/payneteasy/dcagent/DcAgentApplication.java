package com.payneteasy.dcagent;

import com.payneteasy.dcagent.core.modules.docker.runtime.ContainerRuntime;
import com.google.gson.Gson;
import com.payneteasy.apiservlet.GsonJettyContextHandler;
import com.payneteasy.jetty.util.appstatus.AppStatusInfo;
import com.payneteasy.jetty.util.appstatus.AppStatusServlet;
import com.payneteasy.dcagent.controlplane.DcAgentControlPlaneRemoteServiceImpl;
import com.payneteasy.dcagent.controlplane.filter.ControlPlaneBearerFilter;
import com.payneteasy.dcagent.controlplane.service.command.CommandListService;
import com.payneteasy.dcagent.controlplane.service.command.CommandWriteService;
import com.payneteasy.dcagent.controlplane.service.command.ConfigBackupService;
import com.payneteasy.dcagent.controlplane.service.serviceview.ServiceViewDelegate;
import com.payneteasy.dcagent.controlplane.service.supervise.ISuperviseService;
import com.payneteasy.dcagent.controlplane.service.supervise.impl.SuperviseServiceImpl;
import com.payneteasy.dcagent.metrics.SystemInfoCollector;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.config.service.impl.ConfigServiceImpl;
import com.payneteasy.dcagent.core.config.migration.ApiKeyMigration;
import com.payneteasy.dcagent.core.config.validation.ApiKeyHash;
import com.payneteasy.dcagent.core.config.validation.CommandConfigValidator;
import com.payneteasy.dcagent.core.config.validation.CommandPathPolicy;
import com.payneteasy.dcagent.core.config.validation.ConfigCheckMode;
import com.payneteasy.dcagent.core.config.validation.ConfigDirAudit;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemCheckImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import com.payneteasy.dcagent.core.modules.jar.DaemontoolsServiceImpl;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.*;
import com.payneteasy.dcagent.core.modules.zipversion.ReloadClient;
import com.payneteasy.dcagent.core.modules.zipversion.UploadTempFiles;
import com.payneteasy.dcagent.core.util.Strings;
import com.payneteasy.dcagent.core.util.Units;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import com.payneteasy.dcagent.jetty.ErrorFilter;
import com.payneteasy.dcagent.jetty.ExceptionHandlerImpl;
import com.payneteasy.dcagent.jetty.JettyContextRepository;
import com.payneteasy.dcagent.jetty.RequestValidatorImpl;
import com.payneteasy.dcagent.servlets.*;
import com.payneteasy.dcagent.util.SimpleLogImpl;
import com.payneteasy.startup.parameters.StartupParametersFactory;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class DcAgentApplication {

    private static final Logger LOG = LoggerFactory.getLogger(DcAgentApplication.class);

    /**
     * Where the command configs are — fixed, not a startup parameter: a configurable location is
     * one more thing to get wrong, and every installation keeps them here.
     */
    public static final Path CONFIG_DIR = Path.of("/opt/dc-agent/config");

    static final String CONFIG_DIR_VARIABLE = "CONFIG_DIR";
    static final String CHECK_CONFIG        = "check-config";
    static final String HASH_KEY            = "hash-key";

    /** Held while the agent runs: a second agent on the same CONFIG_DIR would migrate it concurrently. */
    static final String CONFIG_LOCK = ConfigBackupService.LOCK_FILE;

    private Server                       jetty;
    private java.nio.channels.FileChannel configLock;

    public static void main(String[] args) {
        useHostTrustStoreInNative(); // before anything can open a TLS connection
        // Route java.util.logging (used by the startup-parameters lib) through SLF4J/Logback
        // so the "Startup parameters" block prints in the same single-line format as the rest.
        // Must run before getStartupParameters(); removeHandlers avoids double (two-line + one-line) output.
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        if (args.length > 0) {
            System.exit(runCommand(args));
        }
        try {
            IStartupConfig     startupConfig = StartupParametersFactory.getStartupParameters(IStartupConfig.class);
            DcAgentApplication app           = new DcAgentApplication();
            app.start(startupConfig);
            app.jetty.setStopAtShutdown(true);
            app.jetty.join();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Cannot start dc-agent", e);
            System.exit(1);
        }
    }

    /** {@code java -jar dc-agent.jar <command>}: the exit code. */
    static int runCommand(String[] aArgs) {
        if (aArgs.length == 1 && CHECK_CONFIG.equals(aArgs[0])) {
            try {
                checkConfigDirVariable(System.getenv(CONFIG_DIR_VARIABLE), System.getProperty(CONFIG_DIR_VARIABLE));
                IStartupConfig config = StartupParametersFactory.getStartupParameters(IStartupConfig.class);
                return checkConfig(config, CONFIG_DIR, System.out);
            } catch (Exception e) {
                System.out.println("check-config failed: " + e.getMessage());
                return 1;
            }
        }
        if (aArgs.length == 1 && HASH_KEY.equals(aArgs[0])) {
            return hashKey(System.in, System.out, System.err);
        }
        System.err.println("Usage: " + selfCommand() + " [" + CHECK_CONFIG + " | " + HASH_KEY + "]");
        System.err.println("  no arguments  run the agent");
        System.err.println("  " + CHECK_CONFIG + "  check " + CONFIG_DIR + " as the agent does at start; exit 0 when it would start");
        System.err.println("  " + HASH_KEY + "      read a key from stdin, print sha256:<hex> to put into apiKeys");
        return 2;
    }

    /**
     * {@code hash-key}: the key is read from stdin (one line), not from the arguments — those are
     * visible in {@code ps} and the shell history.
     */
    static int hashKey(java.io.InputStream aIn, PrintStream aOut, PrintStream aErr) {
        String key;
        try {
            key = new java.io.BufferedReader(new java.io.InputStreamReader(aIn, java.nio.charset.StandardCharsets.UTF_8)).readLine();
        } catch (java.io.IOException e) {
            aErr.println("Cannot read the key from stdin: " + e.getMessage());
            return 1;
        }
        if (key == null || key.length() < ApiKeyHash.MIN_KEY_LENGTH) {
            aErr.println("The key must be at least " + ApiKeyHash.MIN_KEY_LENGTH + " characters (one line on stdin)");
            return 1;
        }
        aOut.println(ApiKeyHash.hash(key));
        return 0;
    }

    /** {@code check-config}: the start-up check of the config directory, printed; 0 when there is no problem. */
    static int checkConfig(IStartupConfig aConfig, Path aConfigDir, PrintStream aOut) {
        CommandConfigValidator validator = commandConfigValidator(aConfig, aConfigDir);
        List<String>           problems  = new ConfigDirAudit(validator).audit(aConfigDir, ConfigCheckMode.PRE_MIGRATION);
        List<String>           planned   = new java.util.ArrayList<>();
        if (problems.isEmpty()) {
            // the start-up migration in memory, then the strict check of its result — nothing is written
            try {
                ApiKeyMigration              migration = new ApiKeyMigration();
                List<ApiKeyMigration.Change> changes   = migration.plan(aConfigDir);
                problems.addAll(migration.problems(changes));
                for (ApiKeyMigration.Change change : changes) {
                    planned.add(change.report());
                    for (String problem : validator.validate(change.name(), change.migrated(), ConfigCheckMode.STRICT)) {
                        problems.add(change.name() + ".json (after hashing): " + problem);
                    }
                }
            } catch (java.io.IOException | RuntimeException e) {
                problems.add("the migration cannot be planned (" + e.getClass().getSimpleName() + ")");
            }
        }
        for (String change : planned) {
            aOut.println("At start the agent will rewrite " + change);
        }
        if (problems.isEmpty()) {
            aOut.println("OK: " + aConfigDir + " — the agent would start");
            return 0;
        }
        aOut.println(aConfigDir + ": " + problems.size() + " problem(s), the agent would refuse to start:");
        for (String problem : problems) {
            aOut.println("  - " + problem);
        }
        return 1;
    }

    private static CommandConfigValidator commandConfigValidator(IStartupConfig aConfig, Path aConfigDir) {
        return new CommandConfigValidator(Gsons.PRETTY_GSON, Units.parseDuration(aConfig.getJettyIdleTimeout()),
                commandPathPolicy(aConfig, aConfigDir));
    }

    /**
     * Where commands may write: inside {@code COMMAND_DIRS}, away from the agent (its home — the
     * parent of CONFIG_DIR —, CONFIG_DIR, its jar as started and as loaded) and the services.
     * A bad {@code COMMAND_DIRS} refuses the start.
     */
    static CommandPathPolicy commandPathPolicy(IStartupConfig aConfig, Path aConfigDir) {
        List<Path> agentPaths = new java.util.ArrayList<>();
        Path       home       = aConfigDir.toAbsolutePath().getParent();
        if (home != null) {
            agentPaths.add(home);
        }
        agentPaths.add(aConfigDir.toAbsolutePath());
        agentPaths.addAll(isNativeImage()
                ? nativeExecutables(argv0(), System.getenv("PATH"), Path.of("").toAbsolutePath(), procSelfExe())
                : agentJars(System.getProperty("java.class.path", ""), codeSource()));
        return new CommandPathPolicy(CommandPathPolicy.parseCommandDirs(aConfig.getCommandDirs()), agentPaths,
                List.of(aConfig.getServicesDir().toPath().toAbsolutePath(), aConfig.getServicesDefinitionDir().toPath().toAbsolutePath()));
    }

    /**
     * The jar as started ({@code java.class.path} keeps the path given to {@code java -jar}, links
     * included) and as loaded ({@code CodeSource}, already resolved); none when not run from a jar.
     */
    static List<Path> agentJars(String aClassPath, Path aCodeSource) {
        // java -jar <file>: the class path is that one file, whatever its name (a link without .jar too)
        List<Path> jars = new java.util.ArrayList<>();
        if (!aClassPath.isEmpty() && !aClassPath.contains(File.pathSeparator)) {
            Path started = Path.of(aClassPath).toAbsolutePath();
            if (java.nio.file.Files.isRegularFile(started)) {
                jars.add(started);
            }
        }
        if (aCodeSource != null && java.nio.file.Files.isRegularFile(aCodeSource)) {
            jars.add(aCodeSource);
        }
        return jars;
    }

    /** How to run this agent in a hint: the binary, or the jar. */
    static String selfCommand() {
        return isNativeImage() ? "dc-agent" : "java -jar dc-agent.jar";
    }

    /** Running as a native image (GraalVM sets this property at image run time). */
    static boolean isNativeImage() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    /**
     * The native executable as started, to protect it with every link on its way, the way
     * {@code execvp} found it: {@code argv[0]} with a {@code /} — as is, relative to the working
     * directory at start; without one — the first executable regular file in {@code PATH} (an empty
     * entry is the working directory). Plus the resolved {@code /proc/self/exe}. A link through
     * which the agent is started ({@code PATH} → {@code /srv/launcher/dc-agent} →
     * {@code /srv/releases/dc-agent}) would otherwise be free to replace.
     */
    static List<Path> nativeExecutables(String aArgv0, String aPath, Path aWorkingDir, Path aProcSelfExe) {
        List<Path> executables = new java.util.ArrayList<>();
        if (aProcSelfExe != null) {
            executables.add(aProcSelfExe);
        }
        if (aArgv0 == null || aArgv0.isEmpty()) {
            return executables;
        }
        if (aArgv0.indexOf('/') >= 0) {
            Path started = Path.of(aArgv0);
            executables.add(started.isAbsolute() ? started : aWorkingDir.resolve(started));
            return executables;
        }
        for (String entry : (aPath == null ? "" : aPath).split(":", -1)) {
            Path dir       = entry.isEmpty() ? aWorkingDir : aWorkingDir.resolve(entry);
            Path candidate = dir.resolve(aArgv0);
            if (java.nio.file.Files.isRegularFile(candidate) && java.nio.file.Files.isExecutable(candidate)) {
                executables.add(candidate);
                break;
            }
        }
        return executables;
    }

    /** {@code argv[0]} of this process, from {@code /proc/self/cmdline}; null when unreadable. */
    private static String argv0() {
        try {
            byte[] cmdline = java.nio.file.Files.readAllBytes(Path.of("/proc/self/cmdline"));
            int    end     = 0;
            while (end < cmdline.length && cmdline[end] != 0) {
                end++;
            }
            return new String(cmdline, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("Cannot read /proc/self/cmdline ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    private static Path procSelfExe() {
        try {
            return java.nio.file.Files.readSymbolicLink(Path.of("/proc/self/exe"));
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("Cannot read /proc/self/exe ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /** Host trust stores, in order: EL (update-ca-trust), Debian/Ubuntu (ca-certificates-java). */
    static final List<Path> HOST_TRUST_STORES = List.of(Path.of("/etc/pki/java/cacerts"), Path.of("/etc/ssl/certs/java/cacerts"));

    /**
     * A native image carries the CA certificates of the JDK it was built with (UBI 9), not of the
     * host: a CA the host trusts (a corporate one) would be refused. Unless
     * {@code javax.net.ssl.trustStore} is given, the native agent uses the host's store.
     */
    static void useHostTrustStoreInNative() {
        if (!isNativeImage() || System.getProperty("javax.net.ssl.trustStore") != null) {
            return;
        }
        // no log here: the CLI commands print their result on stdout, where the log goes too
        Path store = chooseTrustStore(HOST_TRUST_STORES);
        if (store != null) {
            System.setProperty("javax.net.ssl.trustStore", store.toString());
        }
    }

    /** At server start: which CA certificates TLS calls trust (native only). */
    private static void logTrustStore() {
        if (!isNativeImage()) {
            return;
        }
        String store = System.getProperty("javax.net.ssl.trustStore");
        if (store != null) {
            LOG.info("TLS trust store: {}", store);
        } else {
            LOG.warn("TLS trust store: none of {} is readable; using the CA certificates built into the binary", HOST_TRUST_STORES);
        }
    }

    static Path chooseTrustStore(List<Path> aCandidates) {
        for (Path candidate : aCandidates) {
            if (java.nio.file.Files.isRegularFile(candidate) && java.nio.file.Files.isReadable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path codeSource() {
        try {
            java.security.CodeSource source = DcAgentApplication.class.getProtectionDomain().getCodeSource();
            return source != null ? Path.of(source.getLocation().toURI()) : null;
        } catch (java.net.URISyntaxException | RuntimeException e) {
            LOG.warn("Cannot locate the agent jar ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * CONFIG_DIR is no longer a parameter: one set to another directory would leave the configs
     * there unread, so the agent refuses to start instead of silently using {@link #CONFIG_DIR}.
     */
    static void checkConfigDirVariable(String aEnv, String aProperty) {
        for (String value : new String[]{aEnv, aProperty}) {
            if (Strings.isEmpty(value) || value.isBlank()) {
                continue;
            }
            if (!Path.of(value.trim()).toAbsolutePath().normalize().equals(CONFIG_DIR)) {
                throw new IllegalStateException(CONFIG_DIR_VARIABLE + " is set to another directory, but the agent"
                        + " always reads its configs from " + CONFIG_DIR + ": move them there and remove " + CONFIG_DIR_VARIABLE);
            }
            LOG.info("{} is always {}; the variable can be removed", CONFIG_DIR_VARIABLE, CONFIG_DIR);
        }
    }

    public void start(IStartupConfig aConfig) throws Exception {
        checkConfigDirVariable(System.getenv(CONFIG_DIR_VARIABLE), System.getProperty(CONFIG_DIR_VARIABLE));
        start(aConfig, CONFIG_DIR.toFile());
    }

    /** {@code aConfigDir} — {@link #CONFIG_DIR}; another directory only in tests. */
    void start(IStartupConfig aConfig, File aConfigDir) throws Exception {
        logTrustStore();
        checkControlPlaneToken(aConfig.isControlPlaneEnabled(), aConfig.controlPlaneToken());

        Duration               idleTimeout = Units.parseDuration(aConfig.getJettyIdleTimeout());
        CommandConfigValidator validator   = commandConfigValidator(aConfig, aConfigDir.toPath());
        prepareConfigDir(aConfigDir.toPath(), validator);

        jetty = new Server();
        jetty.addConnector(aConfig.isJettyInherited()
                ? createInheritedConnector(jetty, System.inheritedChannel(), idleTimeout)
                : createConnector(jetty, aConfig.getJettyPort(), idleTimeout));

        ServletContextHandler  context       = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath(aConfig.getJettyContext());
        jetty.setHandler(context);
        JettyContextRepository repo          = new JettyContextRepository(context);
        Gson                   gson          = Gsons.PRETTY_GSON;
        IConfigService         configService = new ConfigServiceImpl(aConfigDir, gson, validator);

        DaemontoolsServiceImpl daemontoolsService = new DaemontoolsServiceImpl(
                  aConfig.getSvcCommand()
                , aConfig.getSvstatCommand()
                , new SimpleLogImpl(DcAgentApplication.class)
        );

        repo.add("/zip-archive/*"  , new ZipArchiveServlet(configService));
        repo.add("/zip-dirs/*"     , new ZipDirsServlet(configService));
        UploadTempFiles uploadTempFiles = new UploadTempFiles(Path.of(System.getProperty("java.io.tmpdir")));
        uploadTempFiles.sweep(Duration.ofDays(1));
        repo.add("/zip-archive-version/*", new ZipArchiveVersionServlet(configService, idleTimeout, new ReloadClient(), uploadTempFiles));
        repo.add("/fetch-url/*"    , new FetchUrlServlet(configService));
        repo.add("/save-artifact/*", new SaveArtifactServlet(configService));
        repo.add("/jar/*"          , new FetchUrlServlet.JarServlet(configService, daemontoolsService));
        repo.add("/war/*"          , new WarServlet(configService, daemontoolsService));
        repo.add("/node/*"         , new NodeServlet(configService, daemontoolsService));
        repo.add("/health"         , new HealthServlet());

        repo.add("/app-status/*"   , new AppStatusServlet(
                AppStatusInfo.builder()
                        .jettyConfig     ( aConfig                   )
                        .instanceName    ( aConfig.appInstanceName() )
                        .applicationClass( DcAgentApplication.class          )
                        .bearerToken     ( aConfig.appStatusToken()  )
                        .build()));

        TempDir               tempDir               = new TempDir(aConfig.getTempDir(), aConfig.isDockerDeleteTempDir());
        ServicesDefinitionDir servicesDefinitionDir = new ServicesDefinitionDir(aConfig.getServicesDefinitionDir());
        ServicesLogDir        servicesLogDir        = new ServicesLogDir(aConfig.getServicesLogDir());
        ContainerRuntime      containerRuntime      = ContainerRuntime.parse(aConfig.getContainerRuntime());

        repo.add("/docker/push/*"  , new PushDockerServlet(configService
                , tempDir
                , servicesDefinitionDir
                , servicesLogDir
                , FileSystemWriterImpl::new
                , containerRuntime
        ));

        repo.add("/docker/check/*"  , new PushDockerServlet(configService
                , tempDir
                , servicesDefinitionDir
                , servicesLogDir
                , FileSystemCheckImpl::new
                , containerRuntime
        ));

        repo.addFilter("/*", new ErrorFilter());

        GsonJettyContextHandler handler = new GsonJettyContextHandler(
                context
                , gson
                , new ExceptionHandlerImpl(gson)
                , new RequestValidatorImpl()
        );

        if (aConfig.isControlPlaneEnabled()) {
            ISuperviseService   service             = new SuperviseServiceImpl(aConfig.getServicesDir(), daemontoolsService);
            ServiceViewDelegate serviceViewDelegate = new ServiceViewDelegate(aConfig.getServicesDir(), service);
            CommandListService  commandListService  = new CommandListService(aConfigDir, gson);
            CommandWriteService commandWriteService = new CommandWriteService(aConfigDir, gson, validator);
            ConfigBackupService configBackupService = new ConfigBackupService(aConfigDir);
            SystemInfoCollector systemInfoCollector = new SystemInfoCollector();
            systemInfoCollector.start();
            IDcAgentControlPlaneRemoteService controlPlane        = new DcAgentControlPlaneRemoteServiceImpl(service, serviceViewDelegate, commandListService, commandWriteService, configBackupService, systemInfoCollector, idleTimeout);

            repo.addFilter("/control-plane/api/*", new ControlPlaneBearerFilter(aConfig.controlPlaneToken()));
            handler.addApi("/control-plane/api/service/list"    , controlPlane::listServices , ServiceListRequest.class);
            handler.addApi("/control-plane/api/metrics"         , controlPlane::getSystemInfo, SystemInfoRequest.class);
            handler.addApi("/control-plane/api/service/view/*"  , controlPlane::viewService  , ServiceViewRequest.class);
            handler.addApi("/control-plane/api/service/action/*", controlPlane::sendAction   , ServiceActionRequest.class);
            handler.addApi("/control-plane/api/command/list"    , controlPlane::listCommands , CommandListRequest.class);
            handler.addApi("/control-plane/api/config/backup"   , controlPlane::backupConfigs, ConfigBackupRequest.class);
            handler.addApi("/control-plane/api/command/get"     , controlPlane::getCommand   , CommandGetRequest.class);

            handler.addApi("/control-plane/api/command/create/jar"          , controlPlane::createJar          , CommandJarRequest.class);
            handler.addApi("/control-plane/api/command/create/war"          , controlPlane::createWar          , CommandWarRequest.class);
            handler.addApi("/control-plane/api/command/create/node"         , controlPlane::createNode         , CommandNodeRequest.class);
            handler.addApi("/control-plane/api/command/create/save-artifact", controlPlane::createSaveArtifact , CommandSaveArtifactRequest.class);
            handler.addApi("/control-plane/api/command/create/zip-archive"  , controlPlane::createZipArchive   , CommandZipArchiveRequest.class);
            handler.addApi("/control-plane/api/command/create/zip-dirs"     , controlPlane::createZipDirs      , CommandZipDirsRequest.class);
            handler.addApi("/control-plane/api/command/create/fetch-url"    , controlPlane::createFetchUrl     , CommandFetchUrlRequest.class);
            handler.addApi("/control-plane/api/command/create/docker"       , controlPlane::createDocker       , CommandDockerRequest.class);
            handler.addApi("/control-plane/api/command/create/zip-archive-version", controlPlane::createZipArchiveVersion, CommandZipArchiveVersionRequest.class);

            handler.addApi("/control-plane/api/command/update/jar"          , controlPlane::updateJar          , CommandJarRequest.class);
            handler.addApi("/control-plane/api/command/update/war"          , controlPlane::updateWar          , CommandWarRequest.class);
            handler.addApi("/control-plane/api/command/update/node"         , controlPlane::updateNode         , CommandNodeRequest.class);
            handler.addApi("/control-plane/api/command/update/save-artifact", controlPlane::updateSaveArtifact , CommandSaveArtifactRequest.class);
            handler.addApi("/control-plane/api/command/update/zip-archive"  , controlPlane::updateZipArchive   , CommandZipArchiveRequest.class);
            handler.addApi("/control-plane/api/command/update/zip-dirs"     , controlPlane::updateZipDirs      , CommandZipDirsRequest.class);
            handler.addApi("/control-plane/api/command/update/fetch-url"    , controlPlane::updateFetchUrl     , CommandFetchUrlRequest.class);
            handler.addApi("/control-plane/api/command/update/docker"       , controlPlane::updateDocker       , CommandDockerRequest.class);
            handler.addApi("/control-plane/api/command/update/zip-archive-version", controlPlane::updateZipArchiveVersion, CommandZipArchiveVersionRequest.class);
        }

        removeJettyVersion(jetty);

        jetty.start();
    }

    /**
     * Before the port opens: lock CONFIG_DIR → check it as found (nothing is written when it fails) →
     * hash the plaintext keys → check it strictly. Any problem refuses the start.
     */
    private void prepareConfigDir(Path aConfigDir, CommandConfigValidator aValidator) throws java.io.IOException {
        ConfigDirAudit audit = new ConfigDirAudit(aValidator);
        refuseOn(audit.audit(aConfigDir, ConfigCheckMode.PRE_MIGRATION), aConfigDir);
        lockConfigDir(aConfigDir);

        ApiKeyMigration                migration = new ApiKeyMigration();
        List<ApiKeyMigration.Change>   changes   = migration.plan(aConfigDir);
        refuseOn(migration.problems(changes), aConfigDir); // before the first write
        migration.apply(aConfigDir, changes);
        for (ApiKeyMigration.Change change : changes) {
            if (change.shortKeys() > 0) {
                LOG.warn("Migrated {}", change.report());
            } else {
                LOG.info("Migrated {}", change.report());
            }
        }

        refuseOn(audit.audit(aConfigDir, ConfigCheckMode.STRICT), aConfigDir);
    }

    private static void refuseOn(List<String> aProblems, Path aConfigDir) {
        if (!aProblems.isEmpty()) {
            throw new IllegalStateException("Refusing to start: " + aProblems.size() + " problem(s) in " + aConfigDir
                    + " (" + selfCommand() + " " + CHECK_CONFIG + " lists them too):\n  - " + String.join("\n  - ", aProblems));
        }
    }

    /**
     * Kept for the life of the process; a second agent on the same CONFIG_DIR refuses to start.
     * The channel stays open on success on purpose — closing it would release the lock.
     */
    @SuppressWarnings("java:S2095")
    private void lockConfigDir(Path aConfigDir) throws java.io.IOException {
        java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(aConfigDir.resolve(CONFIG_LOCK),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        java.nio.channels.FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            channel.close();
            throw new IllegalStateException("Refusing to start: another dc-agent holds " + aConfigDir.resolve(CONFIG_LOCK));
        }
        configLock = channel;
    }

    /** Releases the CONFIG_DIR lock (tests; the process exit releases it otherwise). */
    void releaseConfigLock() throws java.io.IOException {
        if (configLock != null) {
            configLock.close();
            configLock = null;
        }
    }

    /** The one HTTP connector, with an explicit idle timeout (Jetty's default of 30 s cuts a waiting call). */
    static ServerConnector createConnector(Server aServer, int aPort, Duration aIdleTimeout) {
        ServerConnector connector = new ServerConnector(aServer);
        connector.setPort(aPort);
        connector.setIdleTimeout(aIdleTimeout.toMillis());
        return connector;
    }

    /**
     * The connector on a listening socket handed over on fd 0 ({@code WEB_SERVER_INHERITED}). Refuses
     * anything but a bound, listening TCP socket: Jetty itself would warn and open its own port.
     */
    static ServerConnector createInheritedConnector(Server aServer, java.nio.channels.Channel aInherited, Duration aIdleTimeout)
            throws java.io.IOException {
        if (!(aInherited instanceof java.nio.channels.ServerSocketChannel listener)
                || !(listener.getLocalAddress() instanceof java.net.InetSocketAddress address)) {
            throw new IllegalStateException("Refusing to start: WEB_SERVER_INHERITED=true, but fd 0 is not a listening TCP socket ("
                    + (aInherited == null ? "nothing inherited" : aInherited.getClass().getSimpleName()) + ")");
        }
        LOG.info("Listening on the inherited socket {}", address);
        ServerConnector connector = new ServerConnector(aServer);
        connector.setInheritChannel(true);
        connector.setIdleTimeout(aIdleTimeout.toMillis());
        return connector;
    }

    Server jetty() {
        return jetty;
    }

    /**
     * The control plane creates commands with their own api keys (then /jar/... deploys and restarts a
     * service), so it must not run behind the token published in this repository or an empty one.
     */
    static void checkControlPlaneToken(boolean aEnabled, String aToken) {
        if (!aEnabled) {
            return;
        }
        if (Strings.isEmpty(aToken) || IStartupConfig.DEFAULT_CONTROL_PLANE_TOKEN.equals(aToken.trim())) {
            throw new IllegalStateException("CONTROL_PLANE_ENABLED=true requires CONTROL_PLANE_TOKEN to be set"
                    + " to your own secret (it is empty or the published default)");
        }
    }

    private void removeJettyVersion(Server jetty) {
        for (Connector connector : jetty.getConnectors()) {
            for (ConnectionFactory connectionFactory : connector.getConnectionFactories()) {
                if (connectionFactory instanceof HttpConnectionFactory httpConnectionFactory) {
                    HttpConfiguration httpConfiguration = httpConnectionFactory.getHttpConfiguration();
                    httpConfiguration.setSendServerVersion(false);
                    httpConfiguration.setSendXPoweredBy(false);
                }
            }
        }
    }


}
