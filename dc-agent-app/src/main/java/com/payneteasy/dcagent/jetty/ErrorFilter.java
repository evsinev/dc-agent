package com.payneteasy.dcagent.jetty;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.payneteasy.dcagent.exception.Problem;
import com.payneteasy.dcagent.exception.ProblemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

public class ErrorFilter implements Filter {

    private static final Logger LOG  = LoggerFactory.getLogger(ErrorFilter.class);
    private final        Gson   gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest aRequest, ServletResponse aResponse, FilterChain aChain) throws IOException, ServletException {
        try {
            aChain.doFilter(aRequest, aResponse);
        } catch (ProblemException e) {
            displayError(aRequest, aResponse, e.getHttpCode(), e);
        } catch (Exception e) {
            displayError(aRequest, aResponse, SC_INTERNAL_SERVER_ERROR, e);
        }
    }

    private void displayError(ServletRequest aRequest, ServletResponse aResponse, int aHttpCode, Exception aException) {
        HttpServletRequest  httpRequest  = (HttpServletRequest) aRequest;
        HttpServletResponse httpResponse = (HttpServletResponse) aResponse;
        String              errorId      = UUID.randomUUID().toString();

        LOG.error("{}: Error processing {}", errorId, httpRequest.getRequestURI(), aException);
        httpResponse.setStatus(aHttpCode);
        httpResponse.setContentType("application/json");
        try {
            gson.toJson(new Problem(aException.getClass().getSimpleName(), aException.getMessage(), errorId), httpResponse.getWriter());
        } catch (IOException e) {
            LOG.error("Cannot write output", e);
        }

    }

    private String logException(HttpServletRequest aRequest, Exception e) {
        String errorId = UUID.randomUUID().toString();
        LOG.error("{}: Error processing {}", errorId, aRequest.getRequestURI(), e);
        return errorId;
    }

    @Override
    public void destroy() {

    }
}
