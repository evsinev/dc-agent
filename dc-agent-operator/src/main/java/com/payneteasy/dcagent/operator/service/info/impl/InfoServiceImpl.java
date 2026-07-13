package com.payneteasy.dcagent.operator.service.info.impl;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.operator.service.info.IInfoService;
import com.payneteasy.dcagent.operator.service.info.messages.InfoResponse;

/**
 * Reports the operator's build version from the jar manifest ({@code Implementation-Version}, set by the
 * {@code operator-shaded} Maven profile to {@code ${project.version}}). Falls back to {@code "dev"} when the
 * app runs unshaded (no manifest version), so the endpoint never returns null.
 */
public class InfoServiceImpl implements IInfoService {

    private final Class<?> appClass;

    public InfoServiceImpl(Class<?> appClass) {
        this.appClass = appClass;
    }

    @Override
    public InfoResponse info(VoidRequest aRequest) {
        return InfoResponse.builder().version(resolveVersion()).build();
    }

    private String resolveVersion() {
        Package pkg     = appClass.getPackage();
        String  version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.isEmpty() ? "dev" : version;
    }
}
