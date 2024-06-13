package com.payneteasy.dcagent.servlets;

import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.config.service.IConfigService;
import com.payneteasy.dcagent.core.modules.jar.DaemontoolsServiceImpl;
import com.payneteasy.dcagent.core.modules.jar.ILog;
import com.payneteasy.dcagent.core.util.Streams;
import com.payneteasy.dcagent.core.util.Strings;
import com.payneteasy.dcagent.jetty.CheckApiKey;
import okhttp3.*;
import okhttp3.logging.HttpLoggingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public class FetchUrlServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(FetchUrlServlet.class);

    private final IConfigService configService;
    private final OkHttpClient   httpClient;
    private final CheckApiKey    checkApiKey = new CheckApiKey();

    public FetchUrlServlet(IConfigService aConfigService) {
        configService = aConfigService;
        Duration timeout = Duration.of(30, ChronoUnit.SECONDS);

        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(message -> LOG.debug("HTTP: {}", message));
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BASIC);

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .followSslRedirects(true)
                .followRedirects(true)
                .addInterceptor(loggingInterceptor)
                .build();
    }

    @Override
    protected void doGet(HttpServletRequest aRequest, HttpServletResponse aResponse) {
        String url = createTargetUrl(aRequest);
        String id  = UUID.randomUUID().toString();

        LOG.debug("{}: Fetching url {} ...", id, url);

        checkApiKey.check(aRequest, configService.getFetchUrlConfig());

        Request  request = new Request.Builder().url(url.toString()).get().build();
        Response response;

        try {
            response = httpClient.newCall(request).execute();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot get " + url);
        }

        aResponse.setStatus(response.code());
        Headers headers = response.headers();
        for (String header : headers.names()) {
            for (String value : headers.values(header)) {
                aResponse.setHeader(header, value);
            }
        }

        ResponseBody body = response.body();
        if (body == null) {
            LOG.debug("{}: body is null", id);
            return;
        }


        InputStream         inputStream  = body.byteStream();
        ServletOutputStream outputStream = null;
        try {
            outputStream = aResponse.getOutputStream();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot get output stream");
        }

        try {
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
