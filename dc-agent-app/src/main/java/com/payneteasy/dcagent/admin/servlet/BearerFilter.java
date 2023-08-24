package com.payneteasy.dcagent.admin.servlet;

import com.payneteasy.dcagent.admin.context.RequestContext;
import com.payneteasy.dcagent.admin.context.RequestContextManager;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

public class BearerFilter implements Filter {

    private final RequestContextManager contextManager;

    public BearerFilter(RequestContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest aRequest, ServletResponse aResponse, FilterChain aChain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) aRequest;

        contextManager.createContext(RequestContext.builder()
                        .accessToken("123")
                        .clientId("123")
                        .clientSecret("123")
                .build());
        try {
             aChain.doFilter(aRequest, aResponse);
        } finally {
            contextManager.clearContext();
        }
    }

    @Override
    public void destroy() {

    }
}
