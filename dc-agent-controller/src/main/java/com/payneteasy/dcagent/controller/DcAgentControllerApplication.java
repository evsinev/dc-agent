package com.payneteasy.dcagent.controller;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.payneteasy.dcagent.controller.servlet.CliCreateJobServlet;
import com.payneteasy.dcagent.controller.servlet.ManageViewJobServlet;
import com.payneteasy.jetty.util.*;
import com.payneteasy.mini.core.app.AppContext;
import org.eclipse.jetty.servlet.ServletContextHandler;

import static com.payneteasy.dcagent.core.util.SafeFiles.createDirs;
import static com.payneteasy.mini.core.app.AppRunner.runApp;
import static com.payneteasy.startup.parameters.StartupParametersFactory.getStartupParameters;

public class DcAgentControllerApplication {

    public static void main(String[] args) {
        runApp(args, DcAgentControllerApplication::run);
    }

    private static void run(AppContext aContext) {
        IControllerStartupConfig config = getStartupParameters(IControllerStartupConfig.class);

        Gson gson = new GsonBuilder()
                .disableHtmlEscaping()
                .setPrettyPrinting()
                .create();

        CliCreateJobServlet createJobServlet = new CliCreateJobServlet(
                createDirs(config.getJobsDir())
                , gson
                , config.getControllerManageBaseUrl() + "/job/"
        );

        JettyServer jetty = new JettyServerBuilder()
                .startupParameters(config)
                .contextOption(JettyContextOption.NO_SESSIONS)

                .filter("/*", new PreventStackTraceFilter())

                .servlet("/health"          , new HealthServlet() )
                .servlet("/cli/create-job/*", createJobServlet    )
                .servlet("/manage/job/*"    , new ManageViewJobServlet() )

                .contextListener(servletContextHandler -> configureApi(servletContextHandler, config))
                .build();

        jetty.startJetty();
    }

    private static void configureApi(ServletContextHandler aHandler, IControllerStartupConfig aConfig) {
        
    }
}
