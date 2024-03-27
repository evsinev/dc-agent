package com.payneteasy.dcagent.operator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.payneteasy.dcagent.controller.filter.HtmlPreventStackTraceFilter;
import com.payneteasy.dcagent.controller.filter.JsonPreventStackTraceFilter;
import com.payneteasy.dcagent.controller.service.errorview.impl.ErrorViewServiceImpl;
import com.payneteasy.dcagent.core.task.send.impl.SendTaskServiceImpl;
import com.payneteasy.dcagent.operator.service.app.IAppService;
import com.payneteasy.dcagent.operator.service.app.impl.AppServiceImpl;
import com.payneteasy.dcagent.operator.service.appview.IAppViewService;
import com.payneteasy.dcagent.operator.service.appview.impl.AppViewServiceImpl;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.impl.OperatorConfigServiceImpl;
import com.payneteasy.dcagent.operator.service.taskcreate.ITaskCreateService;
import com.payneteasy.dcagent.operator.service.taskcreate.impl.TaskCreateServiceImpl;
import com.payneteasy.dcagent.operator.servlet.PageListAppsServlet;
import com.payneteasy.dcagent.operator.servlet.PageViewAppServlet;
import com.payneteasy.freemarker.FreemarkerFactory;
import com.payneteasy.http.client.impl.HttpClientImpl;
import com.payneteasy.jetty.util.HealthServlet;
import com.payneteasy.jetty.util.JettyContextOption;
import com.payneteasy.jetty.util.JettyServer;
import com.payneteasy.jetty.util.JettyServerBuilder;
import com.payneteasy.mini.core.app.AppContext;
import org.eclipse.jetty.servlet.ServletContextHandler;

import java.io.File;

import static com.payneteasy.dcagent.core.util.SafeFiles.ensureDirExists;
import static com.payneteasy.mini.core.app.AppRunner.runApp;
import static com.payneteasy.startup.parameters.StartupParametersFactory.getStartupParameters;

public class DcAgentOperatorApplication {

    public static void main(String[] args) {
        runApp(args, DcAgentOperatorApplication::run);
    }

    private static void run(AppContext aContext) {
        IOperatorStartupConfig config = getStartupParameters(IOperatorStartupConfig.class);

        Gson gson = new GsonBuilder()
                .disableHtmlEscaping()
                .setPrettyPrinting()
                .create();


        File repoDir  = ensureDirExists(config.getRepoDir());
        File tasksDir = ensureDirExists(new File(repoDir, "tasks"));
        File appsDir  = ensureDirExists(new File(repoDir, "apps"));

        IOperatorConfigService configService     = new OperatorConfigServiceImpl(config.getConfigFile());
        FreemarkerFactory      freemarkerFactory = new FreemarkerFactory(new File("."));
        SendTaskServiceImpl    sendTaskService   = new SendTaskServiceImpl(new HttpClientImpl());
        IAppService            appService        = new AppServiceImpl(appsDir);
        ITaskCreateService     taskCreateService = new TaskCreateServiceImpl(tasksDir);
        IAppViewService        appViewService    = new AppViewServiceImpl(sendTaskService, configService, appService, taskCreateService);

        JettyServer jetty = new JettyServerBuilder()
                .startupParameters(config)
                .contextOption(JettyContextOption.NO_SESSIONS)

                .servlet("/health"          , new HealthServlet() )

                .filter("/*"    , new HtmlPreventStackTraceFilter(new ErrorViewServiceImpl(freemarkerFactory)))
                .filter("/api/*", new JsonPreventStackTraceFilter())

                .servlet("/app/*", new PageViewAppServlet(freemarkerFactory, appViewService) )
                .servlet("/*"    , new PageListAppsServlet(freemarkerFactory, appService) )

                .contextListener(servletContextHandler -> configureApi(servletContextHandler, config))
                .build();

        jetty.startJetty();
    }

    private static void configureApi(ServletContextHandler aHandler, IOperatorStartupConfig aConfig) {
        
    }
}
