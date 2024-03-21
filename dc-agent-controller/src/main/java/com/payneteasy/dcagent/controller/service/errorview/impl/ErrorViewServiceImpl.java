package com.payneteasy.dcagent.controller.service.errorview.impl;

import com.payneteasy.dcagent.controller.service.errorview.ErrorViewParam;
import com.payneteasy.dcagent.controller.service.errorview.IErrorViewService;
import com.payneteasy.freemarker.FreemarkerFactory;
import com.payneteasy.freemarker.FreemarkerTemplate;

import java.util.UUID;

public class ErrorViewServiceImpl implements IErrorViewService {

    private final FreemarkerTemplate template;

    public ErrorViewServiceImpl(FreemarkerFactory aFreemarkerFactory) {
        template = aFreemarkerFactory.template("page-error-view.html");
    }

    @Override
    public String getErrorPage(ErrorViewParam aError) {
        return template.instance()
                .add("errorType", aError.getType())
                .add("errorTitle", aError.getTitle())
                .add("errorDescription", aError.getDescription())
                .add("errorId", UUID.randomUUID())
                .createText();
    }
}
