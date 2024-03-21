package com.payneteasy.dcagent.controller;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.payneteasy.dcagent.controller.filter.HtmlPreventStackTraceFilter;
import com.payneteasy.dcagent.controller.filter.JsonPreventStackTraceFilter;
import com.payneteasy.dcagent.controller.service.config.IControllerConfigService;
import com.payneteasy.dcagent.controller.service.config.impl.ControllerConfigServiceImpl;
import com.payneteasy.dcagent.controller.service.errorview.impl.ErrorViewServiceImpl;
import com.payneteasy.dcagent.controller.service.jobview.impl.JobViewServiceImpl;
import com.payneteasy.dcagent.controller.servlet.CliCreateJobServlet;
import com.payneteasy.dcagent.controller.servlet.ManageViewJobServlet;
import com.payneteasy.dcagent.core.task.send.impl.SendTaskServiceImpl;
import com.payneteasy.http.client.impl.HttpClientImpl;
import com.payneteasy.jetty.util.*;
import com.payneteasy.mini.core.app.AppContext;
import org.eclipse.jetty.servlet.ServletContextHandler;
import com.payneteasy.freemarker.FreemarkerFactory;

import java.io.File;

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
                , config.getCertsDir()
        );

        IControllerConfigService configService     = new ControllerConfigServiceImpl(config.getConfigFile());
        FreemarkerFactory        freemarkerFactory = new FreemarkerFactory(new File("."));
        SendTaskServiceImpl      sendTaskService   = new SendTaskServiceImpl(new HttpClientImpl());
        JobViewServiceImpl       jobViewService    = new JobViewServiceImpl(sendTaskService, configService, config.getJobsDir());

        JettyServer jetty = new JettyServerBuilder()
                .startupParameters(config)
                .contextOption(JettyContextOption.NO_SESSIONS)

                .filter("/cli/*"   , new JsonPreventStackTraceFilter())
                .filter("/manage/*", new HtmlPreventStackTraceFilter(new ErrorViewServiceImpl(freemarkerFactory)))

                .servlet("/health"          , new HealthServlet() )
                .servlet("/cli/create-job/*", createJobServlet    )
                .servlet("/manage/job/*"    , new ManageViewJobServlet(freemarkerFactory, jobViewService) )

                .contextListener(servletContextHandler -> configureApi(servletContextHandler, config))
                .build();

        jetty.startJetty();
    }

    private static void configureApi(ServletContextHandler aHandler, IControllerStartupConfig aConfig) {
        
    }
}
