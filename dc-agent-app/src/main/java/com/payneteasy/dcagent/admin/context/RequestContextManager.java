package com.payneteasy.dcagent.admin.context;

public class RequestContextManager implements IRequestContextFinder {

    private static final ThreadLocal<RequestContext> CONTEXT = new ThreadLocal<>();

    public void createContext(RequestContext aContext) {
        if(CONTEXT.get() != null) {
            throw new IllegalStateException("Context already set");
        }
        CONTEXT.set(aContext);
    }

    @Override
    public RequestContext findContext() {
        if(CONTEXT.get() == null) {
            throw new IllegalStateException("No any context");
        }
        return CONTEXT.get();
    }

    public void clearContext() {
        CONTEXT.remove();
    }
}
