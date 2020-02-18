package com.payneteasy.dcagent.jetty;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.http.HttpServlet;
import java.util.EnumSet;

public class JettyContextRepository {

    private static final Logger LOG = LoggerFactory.getLogger(JettyContextRepository.class);

    private final ServletContextHandler context;

    public JettyContextRepository(ServletContextHandler context) {
        this.context = context;
    }

    public void add(String path, HttpServlet servlet) {
        LOG.info("Adding servlet {}", path);
        context.addServlet(new ServletHolder(servlet), path);
    }

    public void addFilter(String path, Filter aFilter) {
        LOG.info("Adding filter {}", path);
        context.addFilter(new FilterHolder(aFilter), path, EnumSet.of(DispatcherType.REQUEST));
    }
}
