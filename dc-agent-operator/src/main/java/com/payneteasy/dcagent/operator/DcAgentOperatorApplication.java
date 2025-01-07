package com.payneteasy.dcagent.operator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.payneteasy.apiservlet.GsonJettyContextHandler;
import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.controller.filter.HtmlPreventStackTraceFilter;
import com.payneteasy.dcagent.controller.filter.JsonPreventStackTraceFilter;
import com.payneteasy.dcagent.controller.service.errorview.impl.ErrorViewServiceImpl;
import com.payneteasy.dcagent.operator.service.app.IAppService;
import com.payneteasy.dcagent.operator.service.app.messages.AppListRequest;
import com.payneteasy.dcagent.operator.service.appview.AppPushRequest;
import com.payneteasy.dcagent.operator.service.appview.AppViewRequest;
import com.payneteasy.dcagent.operator.service.appview.IAppViewService;
import com.payneteasy.dcagent.operator.service.git.IGitService;
import com.payneteasy.dcagent.operator.service.services.ITraitService;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceListRequest;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceSendActionRequest;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceViewRequest;
import com.payneteasy.dcagent.operator.servlet.AssetsServlet;
import com.payneteasy.dcagent.operator.servlet.PageListAppsServlet;
import com.payneteasy.dcagent.operator.servlet.PageReactServlet;
import com.payneteasy.dcagent.operator.servlet.PageViewAppServlet;
import com.payneteasy.freemarker.FreemarkerFactory;
import com.payneteasy.jetty.util.HealthServlet;
import com.payneteasy.jetty.util.JettyContextOption;
import com.payneteasy.jetty.util.JettyServer;
import com.payneteasy.jetty.util.JettyServerBuilder;
import com.payneteasy.mini.core.app.AppContext;
import com.payneteasy.mini.core.error.handler.ApiExceptionHandler;
import com.payneteasy.mini.core.error.handler.ApiRequestValidator;
import org.eclipse.jetty.servlet.ServletContextHandler;

import java.io.File;

import static com.payneteasy.mini.core.app.AppRunner.runApp;
import static com.payneteasy.startup.parameters.StartupParametersFactory.getStartupParameters;

public class DcAgentOperatorApplication {

    public static void main(String[] args) {
        runApp(args, DcAgentOperatorApplication::run);
    }

    private static void run(AppContext aContext) {
        IOperatorStartupConfig config            = getStartupParameters(IOperatorStartupConfig.class);
        FreemarkerFactory      freemarkerFactory = new FreemarkerFactory(new File("."));
        DcOperatorFactory      factory           = new DcOperatorFactory(config);

        JettyServer jetty = new JettyServerBuilder()
                .startupParameters(config)
                .contextOption(JettyContextOption.NO_SESSIONS)

                .servlet("/health"          , new HealthServlet() )

                .filter("/*"    , new HtmlPreventStackTraceFilter(new ErrorViewServiceImpl(freemarkerFactory)))
                .filter("/api/*", new JsonPreventStackTraceFilter())

                .servlet("/app/*"   , new PageViewAppServlet(freemarkerFactory, factory.appViewService() ) )
                .servlet("/list/*"  , new PageListAppsServlet(freemarkerFactory, factory.appService()    ) )
                .servlet("/"        , new PageReactServlet(freemarkerFactory, config.assetsIndexJsUri(), config.assetsIndexCssUri()))
                .servlet("/assets/*", new AssetsServlet(config.assetsIndexJsResource(), config.assetsIndexCssResource()))

                .contextListener(servletContextHandler -> configureApi(servletContextHandler, factory))
                .build();

        jetty.startJetty();
    }

    private static void configureApi(
              ServletContextHandler  aHandler
            , DcOperatorFactory      aFactory
    ) {

        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();

        GsonJettyContextHandler gsonHandler = new GsonJettyContextHandler(
                  aHandler
                , gson
                , new ApiExceptionHandler()
                , new ApiRequestValidator()
        );

        IAppService     appService     = aFactory.appService();
        IAppViewService appViewService = aFactory.appViewService();
        ITraitService   traitService   = aFactory.traitService();
        IGitService     gitService     = aFactory.gitService();

        gsonHandler.addApi("/api/app/list"      , appService::listApps   , AppListRequest.class);
        gsonHandler.addApi("/api/app/view/*"    , appViewService::viewApp, AppViewRequest.class);
        gsonHandler.addApi("/api/app/push/*"    , appViewService::pushApp, AppPushRequest.class);

        gsonHandler.addApi("/api/service/list/*"       , traitService::listServices, HostServiceListRequest.class);
        gsonHandler.addApi("/api/service/view/*"       , traitService::viewService , HostServiceViewRequest.class);
        gsonHandler.addApi("/api/service/send-action/*", traitService::sendAction  , HostServiceSendActionRequest.class);

        gsonHandler.addApi("/api/git/log/*", gitService::log, VoidRequest.class);

    }
}
