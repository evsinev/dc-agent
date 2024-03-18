package com.payneteasy.dcagent.controller;

import com.payneteasy.dcagent.controller.servlet.CliCreateTaskServlet;
import com.payneteasy.jetty.util.*;
import com.payneteasy.mini.core.app.AppContext;
import org.eclipse.jetty.servlet.ServletContextHandler;

import static com.payneteasy.mini.core.app.AppRunner.runApp;
import static com.payneteasy.startup.parameters.StartupParametersFactory.getStartupParameters;

public class DcAgentControllerApplication {

    public static void main(String[] args) {
        runApp(args, DcAgentControllerApplication::run);
    }

    private static void run(AppContext aContext) {
        IControllerStartupConfig config = getStartupParameters(IControllerStartupConfig.class);

        JettyServer jetty = new JettyServerBuilder()
                .startupParameters(config)
                .contextOption(JettyContextOption.NO_SESSIONS)

                .filter("/*", new PreventStackTraceFilter())
                .servlet("/health", new HealthServlet())
                .servlet("/cli/create-task", new CliCreateTaskServlet())

                .contextListener(servletContextHandler -> configureApi(servletContextHandler, config))
                .build();

        jetty.startJetty();
    }

    private static void configureApi(ServletContextHandler aHandler, IControllerStartupConfig aConfig) {
        
    }
}
