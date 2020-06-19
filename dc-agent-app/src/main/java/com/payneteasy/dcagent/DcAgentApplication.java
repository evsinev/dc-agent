package com.payneteasy.dcagent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.payneteasy.dcagent.config.IConfigService;
import com.payneteasy.dcagent.config.IStartupConfig;
import com.payneteasy.dcagent.config.impl.ConfigServiceImpl;
import com.payneteasy.dcagent.jetty.ErrorFilter;
import com.payneteasy.dcagent.jetty.JettyContextRepository;
import com.payneteasy.dcagent.modules.fetchurl.FetchUrlServlet;
import com.payneteasy.dcagent.modules.saveartifact.SaveArtifactServlet;
import com.payneteasy.dcagent.modules.zipachive.ZipArchiveServlet;
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

        repo.add("/zip-archive/*", new ZipArchiveServlet(configService));
        repo.add("/fetch-url/*", new FetchUrlServlet(configService));
        repo.add("/save-artifact/*", new SaveArtifactServlet(configService));
        repo.addFilter("/*", new ErrorFilter());
        
        jetty.start();
    }


}
