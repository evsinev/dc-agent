package com.payneteasy.dcagent.core.exception;

import java.util.Map;
import java.util.TreeMap;

public class HttpProblemBuilder {

    private final IProblemType        type;
    private       String              detail;
    private final Map<String, String> context = new TreeMap<>();

    private String                    memo;
    private final Map<String, String> env = new TreeMap<>();

    private Exception cause;

    public HttpProblemBuilder(IProblemType type) {
        this.type = type;
    }

    public static HttpProblemBuilder problem(IProblemType aType) {
        return new HttpProblemBuilder(aType);
    }

    public HttpProblemBuilder detail(String aDetail) {
        detail = aDetail;
        return this;
    }

    public HttpProblemBuilder memo(String aMemo) {
        memo = aMemo;
        return this;
    }

    public HttpProblemBuilder context(String aKey, String aValue) {
        context.put(aKey, aValue);
        return this;
    }

    public HttpProblemBuilder env(String aKey, String aValue) {
        env.put(aKey, aValue);
        return this;
    }

    public HttpProblemException exception() {
        return exception(null);
    }

    public HttpProblemException exception(Exception e) {
        return new HttpProblemException(
                HttpProblem.builder()
                        .title   ( type.getTitle()  )
                        .type    ( type.getType()   )
                        .status  ( type.getStatus() )
                        .memo    ( memo             )
                        .env     ( env              )
                        .context ( context          )
                        .detail  ( detail           )
                        .build()
                , e
        );
    }


}
