package com.payneteasy.dcagent.core.exception;

public class HttpProblemException extends IllegalStateException {


    private final HttpProblem problem;

    public HttpProblemException(HttpProblem aProblem, Exception aCause) {
        super(aProblem.toString(), aCause);
        problem = aProblem;
    }

    public HttpProblem getProblem() {
        return problem;
    }
}
