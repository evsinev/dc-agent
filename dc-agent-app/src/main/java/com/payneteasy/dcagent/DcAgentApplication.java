package com.payneteasy.dcagent;

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
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemCheckImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import com.payneteasy.dcagent.core.modules.jar.DaemontoolsServiceImpl;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.*;
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

public class DcAgentApplication {

    private static final Logger LOG = LoggerFactory.getLogger(DcAgentApplication.class);

    private Server jetty;

    public static void main(String[] args) {
        // Route java.util.logging (used by the startup-parameters lib) through SLF4J/Logback
        // so the "Startup parameters" block prints in the same single-line format as the rest.
        // Must run before getStartupParameters(); removeHandlers avoids double (two-line + one-line) output.
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
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

    public void start(IStartupConfig aConfig) throws Exception {
        jetty = new Server(aConfig.getJettyPort());

        ServletContextHandler  context       = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath(aConfig.getJettyContext());
        jetty.setHandler(context);
        JettyContextRepository repo          = new JettyContextRepository(context);
        Gson                   gson          = Gsons.PRETTY_GSON;
        IConfigService         configService = new ConfigServiceImpl(aConfig.getConfigDir(), gson);

        DaemontoolsServiceImpl daemontoolsService = new DaemontoolsServiceImpl(
                  aConfig.getSvcCommand()
                , aConfig.getSvstatCommand()
                , new SimpleLogImpl(DcAgentApplication.class)
        );

        repo.add("/zip-archive/*"  , new ZipArchiveServlet(configService));
        repo.add("/zip-dirs/*"     , new ZipDirsServlet(configService));
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

        repo.add("/docker/push/*"  , new PushDockerServlet(configService
                , tempDir
                , servicesDefinitionDir
                , servicesLogDir
                , FileSystemWriterImpl::new
        ));

        repo.add("/docker/check/*"  , new PushDockerServlet(configService
                , tempDir
                , servicesDefinitionDir
                , servicesLogDir
                , FileSystemCheckImpl::new
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
            CommandListService  commandListService  = new CommandListService(aConfig.getConfigDir(), gson);
            CommandWriteService commandWriteService = new CommandWriteService(aConfig.getConfigDir(), gson);
            ConfigBackupService configBackupService = new ConfigBackupService(aConfig.getConfigDir());
            SystemInfoCollector systemInfoCollector = new SystemInfoCollector();
            systemInfoCollector.start();
            IDcAgentControlPlaneRemoteService controlPlane        = new DcAgentControlPlaneRemoteServiceImpl(service, serviceViewDelegate, commandListService, commandWriteService, configBackupService, systemInfoCollector);

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

            handler.addApi("/control-plane/api/command/update/jar"          , controlPlane::updateJar          , CommandJarRequest.class);
            handler.addApi("/control-plane/api/command/update/war"          , controlPlane::updateWar          , CommandWarRequest.class);
            handler.addApi("/control-plane/api/command/update/node"         , controlPlane::updateNode         , CommandNodeRequest.class);
            handler.addApi("/control-plane/api/command/update/save-artifact", controlPlane::updateSaveArtifact , CommandSaveArtifactRequest.class);
            handler.addApi("/control-plane/api/command/update/zip-archive"  , controlPlane::updateZipArchive   , CommandZipArchiveRequest.class);
            handler.addApi("/control-plane/api/command/update/zip-dirs"     , controlPlane::updateZipDirs      , CommandZipDirsRequest.class);
            handler.addApi("/control-plane/api/command/update/fetch-url"    , controlPlane::updateFetchUrl     , CommandFetchUrlRequest.class);
            handler.addApi("/control-plane/api/command/update/docker"       , controlPlane::updateDocker       , CommandDockerRequest.class);
        }

        removeJettyVersion(jetty);

        jetty.start();
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
