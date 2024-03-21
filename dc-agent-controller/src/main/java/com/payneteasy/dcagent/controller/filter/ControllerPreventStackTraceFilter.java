package com.payneteasy.dcagent.controller.filter;

import com.payneteasy.dcagent.controller.service.errorview.ErrorViewParam;
import com.payneteasy.dcagent.controller.service.errorview.IErrorViewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import java.io.IOException;

public class ControllerPreventStackTraceFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(ControllerPreventStackTraceFilter.class);

    private final IErrorViewService errorViewService;

    public ControllerPreventStackTraceFilter(IErrorViewService errorViewService) {
        this.errorViewService = errorViewService;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        try {
            chain.doFilter(request, response);
        } catch (Exception e) {
            String text = errorViewService.getErrorPage(ErrorViewParam.builder()
                            .type("UNKNOWN")
                            .title("Unknown error")
                            .description(e.getMessage())
                    .build());
            long id = System.currentTimeMillis();
            LOG.error("Error while processing trace {}", id, e);
            try {
                response.getOutputStream().println(text);
            } catch (IOException e1) {
                LOG.error("Cannot write error", e1);
            }
        }
    }

    @Override
    public void destroy() {

    }
}
