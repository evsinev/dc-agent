package com.payneteasy.dcagent.modules.docker.resolver;

import com.payneteasy.dcagent.config.model.docker.volumes.FileFetchUrlVolume;
import com.payneteasy.http.client.api.*;
import com.payneteasy.http.client.impl.HttpClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import static com.payneteasy.dcagent.util.SafeFiles.writeFile;
import static com.payneteasy.http.client.api.HttpMethod.GET;

public class FileFetchUrlResolver {

    private static final Logger LOG = LoggerFactory.getLogger( FileFetchUrlResolver.class );

    public FileFetchUrlVolume resolve(FileFetchUrlVolume aUnresolved, ResolverContext aContext) {
        File destination = aContext.fullDestination(aUnresolved.getDestination());
        File source      = aContext.fullSource(aUnresolved.getSource(), destination);

        fetchUrl(aUnresolved.getUrl(), source);

        return aUnresolved.toBuilder()
                .destination(destination.getAbsolutePath())
                .source(source.getAbsolutePath())
                .build();
    }

    private void fetchUrl(String aUnresolvedUrl, File aSource) {
        if(aSource.exists() && aSource.length() > 0) {
            LOG.info("File {} already exists", aSource.getAbsoluteFile());
            return;
        }

        HttpRequest request = HttpRequest.builder()
                .method ( GET  )
                .url    ( aUnresolvedUrl )
                .build();

        HttpRequestParameters params = HttpRequestParameters.builder()
                .timeouts(new HttpTimeouts(30_000, 30_000))
                .build();

        LOG.info("Fetching {}...", aUnresolvedUrl);
        IHttpClient client = new HttpClientImpl(); // or new HttpClientImpl();

        HttpResponse response;
        try {
            response = client.send(request, params);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch url " + aUnresolvedUrl, e);
        }

        writeFile(aSource, response.getBody());
    }
}
