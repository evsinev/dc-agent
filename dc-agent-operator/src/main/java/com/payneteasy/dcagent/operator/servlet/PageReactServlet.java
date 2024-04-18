package com.payneteasy.dcagent.operator.servlet;

import com.payneteasy.freemarker.FreemarkerFactory;
import com.payneteasy.freemarker.FreemarkerTemplate;
import com.payneteasy.jetty.util.SafeHttpServlet;
import com.payneteasy.jetty.util.SafeServletRequest;
import com.payneteasy.jetty.util.SafeServletResponse;

public class PageReactServlet extends SafeHttpServlet {

    private final FreemarkerTemplate template;

    public PageReactServlet(FreemarkerFactory aFreemarkerFactory) {
        template = aFreemarkerFactory.template("page-react-index.html");
    }

    @Override
    protected void doSafeGet(SafeServletRequest aRequest, SafeServletResponse aResponse) {

        aResponse.setContentType("text/html");
        template.instance()
                .write(aResponse);
    }
}
