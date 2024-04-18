package com.payneteasy.dcagent.admin.servlet;

import com.payneteasy.apiservlet.IRequestValidator;

public class RequestValidatorImpl implements IRequestValidator {


    @Override
    public <T> T validateRequest(T aRequest, Class<T> aRequestClass) {
        return aRequest;
    }

}
