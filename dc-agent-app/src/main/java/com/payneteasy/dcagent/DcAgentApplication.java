package com.payneteasy.dcagent;

import com.google.gson.Gson;
import com.payneteasy.apiservlet.GsonJettyContextHandler;
import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.admin.service.IUiAdminService;
import com.payneteasy.dcagent.admin.service.impl.UiAdminServiceImpl;
import com.payneteasy.dcagent.admin.service.messages.RefreshRequest;
import com.payneteasy.dcagent.admin.service.messages.TaskViewRequest;
import com.payneteasy.dcagent.admin.service.messages.TokenRequest;
import com.payneteasy.dcagent.admin.service.messages.UserInfoRequest;
import com.payneteasy.dcagent.admin.service.messages.save.JarConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.tokens.impl.TokensServiceImpl;
import com.payneteasy.dcagent.admin.servlet.CorsFilter;
import com.payneteasy.dcagent.admin.servlet.ExceptionHandlerImpl;
import com.payneteasy.dcagent.admin.servlet.RequestValidatorImpl;
import com.payneteasy.dcagent.controlplane.DcAgentControlPlaneRemoteServiceImpl;
import com.payneteasy.dcagent.controlplane.filter.ControlPlaneBearerFilter;
import com.payneteasy.dcagent.controlplane.service.serviceview.ServiceViewDelegate;
import com.payneteasy.dcagent.controlplane.service.supervise.ISuperviseService;
import com.payneteasy.dcagent.controlplane.service.supervise.impl.SuperviseServiceImpl;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.config.service.impl.ConfigServiceImpl;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemCheckImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import com.payneteasy.dcagent.core.modules.jar.DaemontoolsServiceImpl;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceActionRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceViewRequest;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import com.payneteasy.dcagent.jetty.ErrorFilter;
import com.payneteasy.dcagent.jetty.JettyContextRepository;
import com.payneteasy.dcagent.servlets.*;
import com.payneteasy.dcagent.util.SimpleLogImpl;
import com.payneteasy.startup.parameters.StartupParametersFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DcAgentApplication {

    private static final Logger LOG = LoggerFactory.getLogger(DcAgentApplication.class);

    private Server jetty;

    public static void main(String[] args) {
        try {
            IStartupConfig     startupConfig = StartupParametersFactory.getStartupParameters(IStartupConfig.class);
            DcAgentApplication app           = new DcAgentApplication();
            app.start(startupConfig);
            app.jetty.setStopAtShutdown(true);
            app.jetty.join();
        } catch (Exception e) {
            LOG.error("Cannot start dc-agent", e);
            System.exit(1);
        }
    }

    public void start(IStartupConfig aConfig) throws Exception {
        jetty = new Server(aConfig.webServerPort());

        ServletContextHandler  context       = new ServletContextHandler(jetty, aConfig.webServerContext(), ServletContextHandler.NO_SESSIONS);
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

        TempDir               tempDir               = new TempDir(aConfig.getTempDir());
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

        if(aConfig.isUiAdminEnabled()) {
            IUiAdminService adminService = new UiAdminServiceImpl(
                    gson, aConfig.getConfigDir(), aConfig.getOptDir(), null, new TokensServiceImpl()
            );
            handler.addApi("/ui/api/auth/token"   , adminService::token     , TokenRequest.class);
            handler.addApi("/ui/api/auth/refresh" , adminService::refresh   , RefreshRequest.class);
            handler.addApi("/ui/api/task/list"    , adminService::listTasks , VoidRequest.class);
            handler.addApi("/ui/api/task/jar/get" , adminService::getJarTask, TaskViewRequest.class);
            handler.addApi("/ui/api/task/jar/save", adminService::saveJar   , JarConfigSaveRequest.class);
            handler.addApi("/ui/api/user/info"    , adminService::userInfo  , UserInfoRequest.class);

            repo.addFilter("/ui/api/*", new CorsFilter());
        }

        if (aConfig.isControlPlaneEnabled()) {
            ISuperviseService   service             = new SuperviseServiceImpl(aConfig.getServicesDir(), daemontoolsService);
            ServiceViewDelegate serviceViewDelegate = new ServiceViewDelegate(aConfig.getServicesDir(), service);
            IDcAgentControlPlaneRemoteService controlPlane        = new DcAgentControlPlaneRemoteServiceImpl(service, serviceViewDelegate);

            repo.addFilter("/control-plane/api/*", new ControlPlaneBearerFilter(aConfig.controlPlaneToken()));
            handler.addApi("/control-plane/api/service/list"    , controlPlane::listServices, ServiceListRequest.class);
            handler.addApi("/control-plane/api/service/view/*"  , controlPlane::viewService , ServiceViewRequest.class);
            handler.addApi("/control-plane/api/service/action/*", controlPlane::sendAction  , ServiceActionRequest.class);
        }

        jetty.start();
    }

}
