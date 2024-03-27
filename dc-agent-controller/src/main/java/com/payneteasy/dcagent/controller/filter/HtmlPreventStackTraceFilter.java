package com.payneteasy.dcagent.controller.filter;

import com.payneteasy.dcagent.controller.service.errorview.ErrorViewParam;
import com.payneteasy.dcagent.controller.service.errorview.IErrorViewService;
import com.payneteasy.dcagent.core.exception.HttpProblem;
import com.payneteasy.dcagent.core.exception.HttpProblemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import java.io.IOException;

public class HtmlPreventStackTraceFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(HtmlPreventStackTraceFilter.class);

    private final IErrorViewService errorViewService;

    public HtmlPreventStackTraceFilter(IErrorViewService errorViewService) {
        this.errorViewService = errorViewService;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        try {
            chain.doFilter(request, response);
        } catch (HttpProblemException e) {
            showError(response, e.getProblem(), e);
        } catch (Exception e) {
            showError(response
                    , HttpProblem.builder()
                            .type("UNKNOWN")
                            .title("Unknown error")
                            .detail("Unknown error")
                        .build()
                    , e);
        }
    }

    private void showError(ServletResponse response, HttpProblem aProblem, Exception e) {
        String text = errorViewService.getErrorPage(ErrorViewParam.builder()
                        .type(aProblem.getType())
                        .title(aProblem.getTitle())
                        .description(aProblem.getDetail())
                .build());
        LOG.error("Error while processing trace {}", aProblem, e);
        try {
            response.getOutputStream().println(text);
        } catch (IOException e1) {
            LOG.error("Cannot write error", e1);
        }
    }

    @Override
    public void destroy() {

    }
}
