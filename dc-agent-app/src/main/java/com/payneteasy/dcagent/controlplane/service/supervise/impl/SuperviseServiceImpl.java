package com.payneteasy.dcagent.controlplane.service.supervise.impl;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.controlplane.service.supervise.ISuperviseService;
import com.payneteasy.dcagent.core.modules.jar.DaemontoolsServiceImpl;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceActionType;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStatus;
import com.payneteasy.dcagent.core.util.SafeFiles;
import com.payneteasy.osprocess.api.ProcessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import static com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceErrorType.UNKNOWN_ERROR;

public class SuperviseServiceImpl implements ISuperviseService {

    private static final Logger LOG = LoggerFactory.getLogger( SuperviseServiceImpl.class );

    private final File servicesDir;

    private final ServiceStatusParser    statusParser = new ServiceStatusParser();
    private final DaemontoolsServiceImpl daemontoolsService;

    public SuperviseServiceImpl(File servicesDir, DaemontoolsServiceImpl daemontoolsService) {
        this.servicesDir        = servicesDir;
        this.daemontoolsService = daemontoolsService;
    }

    @Override
    public List<ServiceInfoItem> listServices(VoidRequest aVoid) {
        return SafeFiles.listFiles(servicesDir, it -> it.isDirectory() && !it.getName().startsWith(".")).stream()
                .map(this::getServiceInfo)
                .collect(Collectors.toList());
    }

    @Override
    public ServiceInfoItem getServiceInfo(String aServiceName) {
        return getServiceInfo(getServiceDir(aServiceName));
    }

    private ServiceInfoItem getServiceInfo(File serviceDir) {
        try {
            ServiceStatus status  = statusParser.parseServiceStatus(serviceDir);

            return ServiceInfoItem.builder()
                    .status(status)
                    .name(serviceDir.getName())
                    .build();

        } catch (DaemontoolsException e) {
            LOG.error("Cannot parse {}", serviceDir.getAbsolutePath(), e);
            return ServiceInfoItem.builder()
                    .name(serviceDir.getName())
                    .status(ServiceStatus.builder()
                            .error(e.getError())
                            .build())
                    .build();
        } catch (Exception e) {
            LOG.error("Cannot parse {}", serviceDir.getAbsolutePath(), e);
            return ServiceInfoItem.builder()
                    .name(serviceDir.getName())
                    .status(ServiceStatus.builder()
                            .error(UNKNOWN_ERROR)
                            .build())
                    .build();
        }
    }

    private File getServiceDir(String aServiceName) {
        return new File(servicesDir, aServiceName);
    }

    @Override
    public void sendAction(String aServiceName, ServiceActionType aAction) {
        File serviceDir = new File(servicesDir, aServiceName);
        try {
            daemontoolsService.svc(aServiceName, serviceDir.getAbsolutePath(), aAction.getOption());
        } catch (ProcessException e) {
            throw new IllegalStateException("Cannot send action " + aAction + " to service " + serviceDir.getAbsolutePath(), e);
        }
    }

}
