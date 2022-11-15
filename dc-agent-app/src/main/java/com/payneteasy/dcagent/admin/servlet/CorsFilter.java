package com.payneteasy.dcagent.admin.servlet;

import com.payneteasy.dcagent.util.Enumerations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class CorsFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(CorsFilter.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest aRequest, ServletResponse aResponse, FilterChain aChain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) aRequest;

        LOG.debug("Headers {} ...", httpRequest.getRequestURI());

        for (String headerName : Enumerations.toList(httpRequest.getHeaderNames())) {
            LOG.debug("    {} = {}", headerName, httpRequest.getHeader(headerName));
        }
//        try {
//            LOG.debug("Sleeping 2 secs...");
//            Thread.sleep(2000);
//        } catch (InterruptedException e) {
//            LOG.error("Sleep interrupted", e);
//        }
        HttpServletResponse httpResponse = (HttpServletResponse) aResponse;

        httpResponse.setHeader("Access-Control-Allow-Origin", httpRequest.getHeader("Origin"));
        httpResponse.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS, POST");
        httpResponse.setHeader("Access-Control-Max-Age", "300");
        httpResponse.setHeader("Access-Control-Allow-Headers"
                , "x-requested-with" +
                        ", content-type" +
                        ", debug-security-context" +
                        ", upload-key" +
                        ", Origin" +
                        ", Cookie" +
                        ", X-CSRF-Token" +
                        ", X-Current-Language"
        );
        httpResponse.setHeader("Access-Control-Allow-Credentials", "true");

        httpResponse.setHeader("Expires", "-1");
        httpResponse.setHeader("Cache-Control", "no-cache");
        httpResponse.setHeader("Pragma", "public");

        if("OPTIONS".equals(((HttpServletRequest) aRequest).getMethod())) {
            LOG.debug("CORS end");
            return;
        }

        aChain.doFilter(aRequest, aResponse);
    }

    @Override
    public void destroy() {

    }
}
