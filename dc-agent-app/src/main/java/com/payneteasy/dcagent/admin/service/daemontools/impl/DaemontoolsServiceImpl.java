package com.payneteasy.dcagent.admin.service.daemontools.impl;

import com.payneteasy.dcagent.admin.service.daemontools.IDaemontoolsService;
import com.payneteasy.dcagent.admin.service.daemontools.model.ServiceInfo;
import com.payneteasy.dcagent.admin.service.daemontools.model.ServiceStatus;
import com.payneteasy.dcagent.core.util.Gsons;
import com.payneteasy.dcagent.core.util.SafeFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import static com.payneteasy.dcagent.core.util.SafeFiles.ensureDirExists;

public class DaemontoolsServiceImpl implements IDaemontoolsService {

    private static final Logger LOG = LoggerFactory.getLogger( DaemontoolsServiceImpl.class );

    private final File servicesDir;

    private final ServiceStatusParser statusParser = new ServiceStatusParser();

    public DaemontoolsServiceImpl(File servicesDir) {
        this.servicesDir = ensureDirExists(servicesDir);
    }

    @Override
    public List<ServiceInfo> listServices() {
        return SafeFiles.listFiles(servicesDir, File::isDirectory).stream()
                .map(this::getServiceInfo)
                .collect(Collectors.toList());
    }

    @Override
    public ServiceInfo getServiceInfo(String aServiceName) {
        return getServiceInfo(getServiceDir(aServiceName));
    }

    private ServiceInfo getServiceInfo(File serviceDir) {
        ServiceStatus status     = statusParser.parseServiceStatus(serviceDir);

        return ServiceInfo.builder()
                .status(status)
                .serviceDir(serviceDir)
                .build();
    }

    private File getServiceDir(String aServiceName) {
        return new File(servicesDir, aServiceName);
    }


    public static void main(String[] args) {
        List<ServiceInfo> services = new DaemontoolsServiceImpl(new File("/service")).listServices();
        for (ServiceInfo service : services) {
            LOG.info("service {} \n{}", service.getServiceDir().getName(), Gsons.PRETTY_GSON.toJson(service.getStatus()));

        }
    }
}
