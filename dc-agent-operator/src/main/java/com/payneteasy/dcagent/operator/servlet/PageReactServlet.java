package com.payneteasy.dcagent.operator.servlet;

import com.payneteasy.freemarker.FreemarkerFactory;
import com.payneteasy.freemarker.FreemarkerTemplate;
import com.payneteasy.jetty.util.SafeHttpServlet;
import com.payneteasy.jetty.util.SafeServletRequest;
import com.payneteasy.jetty.util.SafeServletResponse;

public class PageReactServlet extends SafeHttpServlet {

    private final FreemarkerTemplate template;
    private final String             assetsIndexJsLocation;
    private final String             assetsIndexCssLocation;
    private final String             startupTime;

    public PageReactServlet(FreemarkerFactory aFreemarkerFactory, String aAssetsIndexJsLocation, String aAssetsIndexCssLocation) {
        template      = aFreemarkerFactory.template("page-react-index.html");
        assetsIndexJsLocation = aAssetsIndexJsLocation;
        assetsIndexCssLocation = aAssetsIndexCssLocation;
        startupTime = "?t=" + System.currentTimeMillis() + "";
    }

    @Override
    protected void doSafeGet(SafeServletRequest aRequest, SafeServletResponse aResponse) {

        aResponse.setContentType("text/html");

        template.instance()
                .add("ASSETS_INDEX_JS_URI" , assetsIndexJsLocation  + startupTime)
                .add("ASSETS_INDEX_CSS_URI", assetsIndexCssLocation + startupTime)
                .write(aResponse);
    }
}
