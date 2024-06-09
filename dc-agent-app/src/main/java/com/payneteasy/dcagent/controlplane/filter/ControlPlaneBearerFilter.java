package com.payneteasy.dcagent.controlplane.filter;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

import static com.payneteasy.dcagent.core.util.Strings.isEmpty;
import static com.payneteasy.dcagent.jetty.FilterErrors.displayError;

public class ControlPlaneBearerFilter implements Filter {

    private final String bearerTokenValue;

    public ControlPlaneBearerFilter(String aToken) {
        bearerTokenValue = "Bearer " + aToken;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest  httpRequest        = (HttpServletRequest) request;
        String              authorizationValue = httpRequest.getHeader("Authorization");

        if (isEmpty(authorizationValue)) {
            displayError(request, response, 401, "No Authorization header");
            return;
        }

        if (!bearerTokenValue.equals(authorizationValue)) {
            displayError(request, response, 401, "Bad bearer token");
            return;
        }

        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
    }
}
