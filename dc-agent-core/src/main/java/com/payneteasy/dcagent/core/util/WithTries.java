package com.payneteasy.dcagent.core.util;

import java.util.concurrent.Callable;

public class WithTries {

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
