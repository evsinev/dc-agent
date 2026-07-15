package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.modules.jar.DaemontoolsServiceImpl;
import com.payneteasy.dcagent.core.modules.jar.ILog;
import com.payneteasy.dcagent.core.util.SsrfBlockedException;
import com.payneteasy.dcagent.core.util.SsrfGuard;
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

    private static final Logger   LOG           = LoggerFactory.getLogger(FetchUrlServlet.class);
    private static final Duration TIMEOUT       = Duration.ofSeconds(30);
    private static final int      MAX_REDIRECTS = 5;

    private final IConfigService configService;
    private final HttpClient     httpClient;
    private final CheckApiKey    checkApiKey = new CheckApiKey();

    public FetchUrlServlet(IConfigService aConfigService) {
        configService = aConfigService;
        // Do NOT auto-follow redirects: we follow them manually so every hop is SSRF-validated
        // (a public URL could otherwise 3xx-redirect to an internal address). Long-lived client
        // => connection reuse.
        httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    protected void doGet(HttpServletRequest aRequest, HttpServletResponse aResponse) {
        String url = createTargetUrl(aRequest);
        String id  = UUID.randomUUID().toString();

        LOG.debug("{}: Fetching url {} ...", id, Strings.forLog(url));

        checkApiKey.check(aRequest, configService.getFetchUrlConfig());

        HttpResponse<InputStream> response;
        try {
            response = fetch(url, id);
        } catch (SsrfBlockedException e) {
            LOG.warn("{}: blocked url {}: {}", id, Strings.forLog(url), e.getMessage());
            aResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
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

    // Follow redirects manually so every hop is SSRF-validated (the client does NOT auto-follow).
    private HttpResponse<InputStream> fetch(String aStartUrl, String aId) {
        // fetch-url is an intentional, api-key-gated proxy of a caller-supplied URL (see
        // website/src/content/docs/commands/fetch-url.mdx). SsrfGuard validates the initial URL and
        // every redirect hop below (rejects non-http(s), loopback, private, link-local incl. cloud
        // metadata). CodeQL can't see the custom guard as a sanitizer, so the sink is suppressed inline.
        URI target = SsrfGuard.validate(aStartUrl);

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(target) // codeql[java/ssrf] — target is SsrfGuard-validated (initial + each redirect hop)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<InputStream> response = send(request, target);

            String location = isRedirect(response.statusCode())
                    ? response.headers().firstValue("Location").orElse(null)
                    : null;
            if (location == null) {
                return response;
            }

            try (InputStream ignored = response.body()) {
                // drain/close the redirect body before following the next hop
            } catch (IOException e) {
                LOG.debug("{}: cannot close redirect body", aId, e);
            }
            target = SsrfGuard.validate(target.resolve(location).toString());
        }
        throw new SsrfBlockedException("Too many redirects");
    }

    private HttpResponse<InputStream> send(HttpRequest aRequest, URI aTarget) {
        try {
            return httpClient.send(aRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot get " + aTarget, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching " + aTarget, e);
        }
    }

    private static boolean isRedirect(int aStatus) {
        return aStatus == 301 || aStatus == 302 || aStatus == 303 || aStatus == 307 || aStatus == 308;
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
