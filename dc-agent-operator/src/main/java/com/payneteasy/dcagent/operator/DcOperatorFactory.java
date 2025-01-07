package com.payneteasy.dcagent.operator;

import com.payneteasy.dcagent.core.remote.agent.controlplane.client.DcAgentControlPlaneClientFactory;
import com.payneteasy.dcagent.core.task.send.ISendTaskService;
import com.payneteasy.dcagent.core.task.send.impl.SendTaskServiceImpl;
import com.payneteasy.dcagent.operator.service.app.IAppService;
import com.payneteasy.dcagent.operator.service.app.impl.AppServiceImpl;
import com.payneteasy.dcagent.operator.service.appview.IAppViewService;
import com.payneteasy.dcagent.operator.service.appview.impl.AppViewServiceImpl;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.impl.OperatorConfigServiceImpl;
import com.payneteasy.dcagent.operator.service.git.IGitService;
import com.payneteasy.dcagent.operator.service.git.impl.GitServiceImpl;
import com.payneteasy.dcagent.operator.service.services.ITraitService;
import com.payneteasy.dcagent.operator.service.services.impl.ListServicesDelegate;
import com.payneteasy.dcagent.operator.service.services.impl.SendActionServiceDelegate;
import com.payneteasy.dcagent.operator.service.services.impl.TraitServiceImpl;
import com.payneteasy.dcagent.operator.service.services.impl.ViewServiceDelegate;
import com.payneteasy.dcagent.operator.service.taskcreate.ITaskCreateService;
import com.payneteasy.dcagent.operator.service.taskcreate.impl.TaskCreateServiceImpl;
import com.payneteasy.http.client.impl.HttpClientImpl;
import com.payneteasy.mini.core.context.IServiceContext;
import com.payneteasy.mini.core.context.IServiceCreator;
import com.payneteasy.mini.core.context.ServiceContextImpl;

import java.io.File;

import static com.payneteasy.dcagent.core.util.SafeFiles.ensureDirExists;

public class DcOperatorFactory {

    private final IOperatorStartupConfig config;
    private final IServiceContext        serviceContext = new ServiceContextImpl();
    private final File                   tasksDir;
    private final File                   appsDir;

    public DcOperatorFactory(IOperatorStartupConfig config) {
        this.config = config;
        File repoDir  = ensureDirExists(config.getRepoDir());
        tasksDir = ensureDirExists(new File(repoDir, "tasks"));
        appsDir  = ensureDirExists(new File(repoDir, "apps"));

    }

    private <T> T singleton(Class<? super T> aClass, IServiceCreator<T> aCreator) {
        return serviceContext.singleton(aClass, aCreator);
    }

    IAppService appService() {
        return singleton(IAppService.class, () -> new AppServiceImpl(appsDir));
    }

    ISendTaskService sendTaskService() {
        return singleton(ISendTaskService.class, () -> new SendTaskServiceImpl(new HttpClientImpl()));
    }

    IOperatorConfigService operatorConfigService() {
        return singleton(IOperatorConfigService.class, () ->
                new OperatorConfigServiceImpl(
                        config.getConfigFile()
                        , getClientFactory()
                )
        );
    }

    ITaskCreateService taskCreateService() {
        return singleton(ITaskCreateService.class, () -> new TaskCreateServiceImpl(tasksDir));
    }

    IAppViewService appViewService() {
        return singleton(IAppViewService.class, () -> new AppViewServiceImpl(
                sendTaskService()
                , operatorConfigService()
                , appService()
                , taskCreateService()
        ));
    }

    ITraitService traitService() {
        return singleton(ITraitService.class, () -> new TraitServiceImpl(
                  new ListServicesDelegate(operatorConfigService())
                , new ViewServiceDelegate(operatorConfigService())
                , new SendActionServiceDelegate(operatorConfigService())
        ));
    }

    DcAgentControlPlaneClientFactory getClientFactory() {
        return singleton(DcAgentControlPlaneClientFactory.class, () -> new DcAgentControlPlaneClientFactory(
                new HttpClientImpl()
        ));
    }

    public IGitService gitService() {
        return singleton(IGitService.class, () -> new GitServiceImpl(config.getRepoDir()));
    }
}
