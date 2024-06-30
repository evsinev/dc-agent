package com.payneteasy.dcagent.operator.servlet;

import com.payneteasy.jetty.util.SafeHttpServlet;
import com.payneteasy.jetty.util.SafeServletRequest;
import com.payneteasy.jetty.util.SafeServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URL;

import static com.payneteasy.dcagent.core.util.Streams.readAllBytes;

public class AssetsServlet extends SafeHttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger( AssetsServlet.class );

    private final byte[] indexJsBytes;
    private final byte[] indexCssBytes;

    public AssetsServlet(String aIndexJsPath, String aIndexCssPath) {
        indexJsBytes  = fetch(aIndexJsPath);
        indexCssBytes = fetch(aIndexCssPath);
    }

    private static byte[] fetch(String aPath) {
        LOG.info("Loading {}", aPath);

        
        URL url = createUrl(aPath);

        try {
            try(InputStream in = url.openStream()) {
                return readAllBytes(in);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch resource " + aPath);
        }
    }

    private static URL createUrl(String aPath) {
        if (aPath.startsWith("classpath:")) {
            return Thread.currentThread().getContextClassLoader().getResource(aPath.replace("classpath:", ""));
        }

        return loadFromUrl(aPath);
    }

    private static URL loadFromUrl(String aPath) {
        try {
            return new URL(aPath);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create url for " + aPath, e);
        }
    }

    @Override
    protected void doSafeGet(SafeServletRequest aRequest, SafeServletResponse aResponse) {
        String url = aRequest.getRequestUrl();

        if (url.endsWith(".js")) {
            aResponse.setContentType("text/javascript");
            aResponse.setHeader("Cache-Control", "max-age=31536000, public, max-age=29030400");
            aResponse.writeBytes(indexJsBytes);
            return;
        }

        if (url.endsWith(".css")) {
            aResponse.setContentType("text/css");
            aResponse.setHeader("Cache-Control", "max-age=31536000, public, max-age=29030400");
            aResponse.writeBytes(indexCssBytes);
            return;
        }

        aResponse.showErrorPage(404, "Resource " + url + " not found");

    }
}
