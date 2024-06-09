package com.payneteasy.dcagent.jetty;

import com.payneteasy.dcagent.core.exception.Problem;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

public class FilterErrors {

    private static final Logger LOG = LoggerFactory.getLogger( FilterErrors.class );

    public static void displayError(
              ServletRequest aRequest
            , ServletResponse aResponse
            , int             aHttpCode
            , String          aErrorMessage
            , Exception       aException
    ) {
        HttpServletRequest  httpRequest  = (HttpServletRequest) aRequest;
        HttpServletResponse httpResponse = (HttpServletResponse) aResponse;
        String              errorId      = UUID.randomUUID().toString();

        LOG.error("{}: Error processing {}: {}", errorId, httpRequest.getRequestURI(), aErrorMessage, aException);
        httpResponse.setStatus(aHttpCode);
        httpResponse.setContentType("application/json");
        try {
            Gsons.PRETTY_GSON.toJson(new Problem(aException.getClass().getSimpleName(), aErrorMessage, errorId), httpResponse.getWriter());
        } catch (IOException e) {
            LOG.error("Cannot write output", e);
        }

    }

    public static void displayError(
              ServletRequest aRequest
            , ServletResponse aResponse
            , int             aHttpCode
            , String          aErrorMessage
    ) {
        HttpServletRequest  httpRequest  = (HttpServletRequest) aRequest;
        HttpServletResponse httpResponse = (HttpServletResponse) aResponse;
        String              errorId      = UUID.randomUUID().toString();

        LOG.error("{}: Error processing {}: {}", errorId, httpRequest.getRequestURI(), aErrorMessage);
        httpResponse.setStatus(aHttpCode);
        httpResponse.setContentType("application/json");
        try {
            Gsons.PRETTY_GSON.toJson(new Problem(aHttpCode + "", aErrorMessage, errorId), httpResponse.getWriter());
        } catch (IOException e) {
            LOG.error("Cannot write output", e);
        }

    }

}
