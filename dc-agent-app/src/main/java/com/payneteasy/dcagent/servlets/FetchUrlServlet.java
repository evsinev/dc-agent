package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.modules.jar.DaemontoolsServiceImpl;
import com.payneteasy.dcagent.core.modules.jar.ILog;
import com.payneteasy.dcagent.core.util.Streams;
import com.payneteasy.dcagent.core.util.Strings;
import com.payneteasy.dcagent.jetty.CheckApiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FetchUrlServlet extends HttpServlet {

    private static final Logger   LOG     = LoggerFactory.getLogger(FetchUrlServlet.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final IConfigService configService;
    private final HttpClient     httpClient;
    private final CheckApiKey    checkApiKey = new CheckApiKey();

    public FetchUrlServlet(IConfigService aConfigService) {
        configService = aConfigService;
        // NORMAL follows redirects but not HTTPS->HTTP downgrades (was okhttp
        // followRedirects+followSslRedirects). Long-lived client => connection reuse.
        httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    protected void doGet(HttpServletRequest aRequest, HttpServletResponse aResponse) {
        String url = createTargetUrl(aRequest);
        String id  = UUID.randomUUID().toString();

        LOG.debug("{}: Fetching url {} ...", id, Strings.forLog(url));

        checkApiKey.check(aRequest, configService.getFetchUrlConfig());

        HttpRequest request = HttpRequest.newBuilder()
                // fetch-url is an intentional, api-key-gated proxy of a caller-supplied URL
                // (see website/src/content/docs/commands/fetch-url.mdx) — SSRF is by design here
                // and the api-key is the access control.
                // codeql[java/ssrf]
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot get " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching " + url, e);
        }

        LOG.debug("{}: got status {} for {}", id, response.statusCode(), Strings.forLog(url));

        aResponse.setStatus(response.statusCode());
        for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
            for (String value : header.getValue()) {
                aResponse.addHeader(header.getKey(), value);
            }
        }

        ServletOutputStream outputStream;
        try {
            outputStream = aResponse.getOutputStream();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot get output stream");
        }

        try (InputStream inputStream = response.body()) {
            Streams.copy(inputStream, outputStream);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot copy streams");
        }
    }

    private String createTargetUrl(HttpServletRequest aRequest) {
        StringBuilder url = new StringBuilder();
        url.append(aRequest.getPathInfo().substring(1));
        if (Strings.hasText(aRequest.getQueryString())) {
            url.append('?');
            url.append(aRequest.getQueryString());
        }
        return url.toString();
    }


    public static class JarServlet extends AbstractJarServlet {

        public JarServlet(IConfigService configService, DaemontoolsServiceImpl aDaemontoolsService) {
            super(configService, aDaemontoolsService);
        }

        @Override
        protected File getJarFile(TJarConfig jarConfig) {
            return new File(jarConfig.getJarFilename());
        }

        @Override
        protected void postProcessJarFile(ILog log, File aWarFile) {

        }
    }
}
