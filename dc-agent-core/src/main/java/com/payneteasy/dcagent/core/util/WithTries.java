package com.payneteasy.dcagent.core.util;

import com.payneteasy.dcagent.core.exception.IProblemType;

import java.util.concurrent.Callable;

import static com.payneteasy.dcagent.core.exception.HttpProblemBuilder.problem;

public class WithTries {


    public static <T> T withProblem(Callable<T> aSupplier, IProblemType aType) {
        try {
            return aSupplier.call();
        } catch (Exception e) {
            throw problem(aType)
                    .exception(e);
        }
    }

    public static <T> T withTry(Callable<T> aSupplier, String aMessage) {
        try {
            return aSupplier.call();
        } catch (Exception e) {
            throw new IllegalStateException(aMessage, e);
        }
    }

    public static void tryCall(ITryCall aCall, String aMessage) {
        try {
            aCall.call();
        } catch (Exception e) {
            throw new IllegalStateException(aMessage, e);
        }
    }

    public interface ITryCall {
        void call() throws Exception;
    }


}
