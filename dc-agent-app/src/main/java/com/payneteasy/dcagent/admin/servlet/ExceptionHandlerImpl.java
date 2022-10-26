package com.payneteasy.dcagent.admin.servlet;

import com.google.gson.Gson;
import com.payneteasy.apiservlet.IExceptionContext;
import com.payneteasy.apiservlet.IExceptionHandler;
import com.payneteasy.dcagent.admin.service.messages.ProblemDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

public class ExceptionHandlerImpl implements IExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ExceptionHandlerImpl.class);

    private final Gson gson;

    public ExceptionHandlerImpl(Gson gson) {
        this.gson = gson;
    }

    @Override
    public void handleException(Exception e, IExceptionContext aContext) {
        String errorId = UUID.randomUUID().toString();
        LOG.error("Unknown error", e);

        ProblemDetail problem = ProblemDetail.builder()
                .errorId(errorId)
                .status(500)
                .title("Unknown error")
                .type("unknown-error")
                .detail("Something went wrong")
                .build();

        writeProblem(aContext, problem);
    }

    private void writeProblem(IExceptionContext aContext, ProblemDetail problem) {
        HttpServletResponse response = aContext.getHttpResponse();
        response.setStatus(problem.getStatus());
        response.setHeader("Content-Type", "application/problem+json; charset=utf-8");

        try {
            response.getWriter().write(gson.toJson(problem));
        } catch (IOException ex) {
            LOG.error("Cannot write error", ex);
        }
    }
}
