package com.payneteasy.dcagent.jetty;

import com.payneteasy.dcagent.core.util.Strings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.payneteasy.dcagent.core.exception.Problem;
import com.payneteasy.dcagent.core.exception.ProblemException;
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
        // the request id of AuditFilter: one id in the error log, the answer and the audit line
        Object              requestId    = httpRequest.getAttribute(AuditFilter.REQUEST_ID);
        String              errorId      = requestId instanceof String id ? id : UUID.randomUUID().toString();

        if (LOG.isErrorEnabled()) {
            LOG.error("{}: Error processing {}", errorId, Strings.forLog(httpRequest.getRequestURI()), aException);
        }
        httpResponse.setStatus(aHttpCode);
        httpResponse.setContentType("application/json");
        try {
            gson.toJson(problem(aException, errorId), httpResponse.getWriter());
        } catch (IOException e) {
            LOG.error("Cannot write output", e);
        }

    }

    /**
     * A {@link ProblemException} is written for the caller and goes out as is. Anything else may carry
     * a path, a file name or a config value: the caller gets the error id only, the log has the rest.
     */
    static Problem problem(Exception aException, String aErrorId) {
        if (aException instanceof ProblemException) {
            return new Problem(aException.getClass().getSimpleName(), aException.getMessage(), aErrorId);
        }
        return new Problem("InternalError", "Internal error, see the agent log for " + aErrorId, aErrorId);
    }

    private String logException(HttpServletRequest aRequest, Exception e) {
        String errorId = UUID.randomUUID().toString();
        if (LOG.isErrorEnabled()) {
            LOG.error("{}: Error processing {}", errorId, Strings.forLog(aRequest.getRequestURI()), e);
        }
        return errorId;
    }

}
