package com.payneteasy.dcagent.controller.filter;

import com.payneteasy.dcagent.core.exception.HttpProblem;
import com.payneteasy.dcagent.core.exception.HttpProblemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static com.payneteasy.dcagent.core.util.Gsons.PRETTY_GSON;

public class JsonPreventStackTraceFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(JsonPreventStackTraceFilter.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        try {
            chain.doFilter(request, response);
        } catch (HttpProblemException e) {
            writeProblem(response, e.getProblem(), e);
        } catch (Exception e) {
            writeProblem(response, HttpProblem.builder()
                    .status(500)
                    .type("UNKNOWN")
                    .detail(e.getMessage())
                    .build(), e);
        }
    }

    private void writeProblem(ServletResponse response, HttpProblem problem, Exception e) {
        LOG.error("Error is {}", problem, e);
        try {
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(problem.getStatus());
            response.getOutputStream().println(PRETTY_GSON.toJson(problem));
        } catch (IOException e2) {
            LOG.error("Cannot write error", e2);
        }
    }

    @Override
    public void destroy() {

    }
}
