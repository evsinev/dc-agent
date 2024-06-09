package com.payneteasy.dcagent.controlplane.service.daemontools.impl;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.controlplane.service.daemontools.IDaemontoolsService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStatus;
import com.payneteasy.dcagent.core.util.SafeFiles;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import static com.payneteasy.apiservlet.VoidRequest.VOID_REQUEST;
import static com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceErrorType.UNKNOWN_ERROR;
import static com.payneteasy.dcagent.core.util.SafeFiles.ensureDirExists;

public class DaemontoolsServiceImpl implements IDaemontoolsService {

    private static final Logger LOG = LoggerFactory.getLogger( DaemontoolsServiceImpl.class );

    private final File servicesDir;

    private final ServiceStatusParser statusParser = new ServiceStatusParser();

    public DaemontoolsServiceImpl(File servicesDir) {
        this.servicesDir = ensureDirExists(servicesDir);
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


    public static void main(String[] args) {
        List<ServiceInfoItem> services = new DaemontoolsServiceImpl(new File("/service")).listServices(VOID_REQUEST);
        for (ServiceInfoItem service : services) {
            LOG.info("service {} \n{}", service.getName(), Gsons.PRETTY_GSON.toJson(service.getStatus()));

        }
    }
}
