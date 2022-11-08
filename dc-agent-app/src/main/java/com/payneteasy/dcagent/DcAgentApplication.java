package com.payneteasy.dcagent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.payneteasy.apiservlet.GsonJettyContextHandler;
import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.admin.service.IUiAdminService;
import com.payneteasy.dcagent.admin.service.impl.UiAdminServiceImpl;
import com.payneteasy.dcagent.admin.service.messages.TaskViewRequest;
import com.payneteasy.dcagent.admin.servlet.CorsFilter;
import com.payneteasy.dcagent.admin.servlet.RequestValidatorImpl;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.config.service.impl.ConfigServiceImpl;
import com.payneteasy.dcagent.jetty.ErrorFilter;
import com.payneteasy.dcagent.admin.servlet.ExceptionHandlerImpl;
import com.payneteasy.dcagent.jetty.JettyContextRepository;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemCheckImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import com.payneteasy.dcagent.servlets.FetchUrlServlet;
import com.payneteasy.dcagent.servlets.NodeServlet;
import com.payneteasy.dcagent.servlets.SaveArtifactServlet;
import com.payneteasy.dcagent.servlets.PushDockerServlet;
import com.payneteasy.dcagent.servlets.WarServlet;
import com.payneteasy.dcagent.servlets.ZipArchiveServlet;
import com.payneteasy.startup.parameters.StartupParametersFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DcAgentApplication {

    private static final Logger LOG = LoggerFactory.getLogger(DcAgentApplication.class);

    private Server jetty;


    public static void main(String[] args) throws Exception {
        IStartupConfig     startupConfig = StartupParametersFactory.getStartupParameters(IStartupConfig.class);
        DcAgentApplication app           = new DcAgentApplication();
        app.start(startupConfig);
        app.jetty.setStopAtShutdown(true);
        app.jetty.join();
    }

    public void start(IStartupConfig aConfig) throws Exception {
        jetty = new Server(aConfig.webServerPort());

        ServletContextHandler  context       = new ServletContextHandler(jetty, aConfig.webServerContext(), ServletContextHandler.NO_SESSIONS);
        JettyContextRepository repo          = new JettyContextRepository(context);
        Gson                   gson          = new GsonBuilder().setPrettyPrinting().create();
        IConfigService         configService = new ConfigServiceImpl(aConfig.getConfigDir(), gson);

        repo.add("/zip-archive/*"  , new ZipArchiveServlet(configService));
        repo.add("/fetch-url/*"    , new FetchUrlServlet(configService));
        repo.add("/save-artifact/*", new SaveArtifactServlet(configService));
        repo.add("/jar/*"          , new FetchUrlServlet.JarServlet(configService));
        repo.add("/war/*"          , new WarServlet(configService));
        repo.add("/node/*"         , new NodeServlet(configService));


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

        IUiAdminService adminService = new UiAdminServiceImpl(
                gson, aConfig.getConfigDir(), aConfig.getOptDir()
        );

        handler.addApi("/ui/api/task/list"    , adminService::listTasks , VoidRequest.class);
        handler.addApi("/ui/api/task/jar/get" , adminService::getJarTask, TaskViewRequest.class);

        repo.addFilter("/ui/api/*", new CorsFilter());

        jetty.start();
    }


}
