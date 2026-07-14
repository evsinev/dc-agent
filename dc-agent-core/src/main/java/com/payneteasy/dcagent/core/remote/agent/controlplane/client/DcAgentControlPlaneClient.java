package com.payneteasy.dcagent.core.remote.agent.controlplane.client;

import com.google.gson.Gson;
import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.*;
import com.payneteasy.http.client.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;

public class DcAgentControlPlaneClient implements IDcAgentControlPlaneRemoteService {

    private static final Logger LOG = LoggerFactory.getLogger( DcAgentControlPlaneClient.class );

    private final IHttpClient           httpClient;
    private final String                baseUrl;
    private final String                bearerToken;
    private final HttpRequestParameters requestParameters;
    private final Gson                  gson;

    public DcAgentControlPlaneClient(IHttpClient httpClient, String baseUrl, String bearerToken, HttpRequestParameters requestParameters, Gson gson) {
        this.httpClient        = httpClient;
        this.baseUrl           = baseUrl;
        this.bearerToken       = bearerToken;
        this.requestParameters = requestParameters;
        this.gson              = gson;
    }

    @Override
    public ServiceListResponse listServices(ServiceListRequest aRequest) {
        return post("/control-plane/api/service/list", aRequest, ServiceListResponse.class);
    }

    @Override
    public ServiceViewResponse viewService(ServiceViewRequest aRequest) {
        return post("/control-plane/api/service/view/" + aRequest.getServiceName(), aRequest, ServiceViewResponse.class);
    }

    @Override
    public ServiceActionResponse sendAction(ServiceActionRequest aRequest) {
        return post(
                "/control-plane/api/service/action/" + aRequest.getServiceName() + "/" + aRequest.getServiceAction()
                , aRequest
                , ServiceActionResponse.class);
    }

    @Override
    public CommandListResponse listCommands(CommandListRequest aRequest) {
        return post("/control-plane/api/command/list", aRequest, CommandListResponse.class);
    }

    @Override
    public ConfigBackupResponse backupConfigs(ConfigBackupRequest aRequest) {
        return post("/control-plane/api/config/backup", aRequest, ConfigBackupResponse.class);
    }

    @Override
    public SystemInfoResponse getSystemInfo(SystemInfoRequest aRequest) {
        return post("/control-plane/api/metrics", aRequest, SystemInfoResponse.class);
    }

    @Override
    public CommandGetResponse getCommand(CommandGetRequest aRequest) {
        return post("/control-plane/api/command/get", aRequest, CommandGetResponse.class);
    }

    @Override public CommandSaveResponse createJar(CommandJarRequest aRequest)                   { return post("/control-plane/api/command/create/jar", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse createWar(CommandWarRequest aRequest)                   { return post("/control-plane/api/command/create/war", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse createNode(CommandNodeRequest aRequest)                 { return post("/control-plane/api/command/create/node", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse createSaveArtifact(CommandSaveArtifactRequest aRequest) { return post("/control-plane/api/command/create/save-artifact", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse createZipArchive(CommandZipArchiveRequest aRequest)     { return post("/control-plane/api/command/create/zip-archive", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse createZipDirs(CommandZipDirsRequest aRequest)           { return post("/control-plane/api/command/create/zip-dirs", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse createFetchUrl(CommandFetchUrlRequest aRequest)         { return post("/control-plane/api/command/create/fetch-url", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse createDocker(CommandDockerRequest aRequest)             { return post("/control-plane/api/command/create/docker", aRequest, CommandSaveResponse.class); }

    @Override public CommandSaveResponse updateJar(CommandJarRequest aRequest)                   { return post("/control-plane/api/command/update/jar", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse updateWar(CommandWarRequest aRequest)                   { return post("/control-plane/api/command/update/war", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse updateNode(CommandNodeRequest aRequest)                 { return post("/control-plane/api/command/update/node", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse updateSaveArtifact(CommandSaveArtifactRequest aRequest) { return post("/control-plane/api/command/update/save-artifact", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse updateZipArchive(CommandZipArchiveRequest aRequest)     { return post("/control-plane/api/command/update/zip-archive", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse updateZipDirs(CommandZipDirsRequest aRequest)           { return post("/control-plane/api/command/update/zip-dirs", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse updateFetchUrl(CommandFetchUrlRequest aRequest)         { return post("/control-plane/api/command/update/fetch-url", aRequest, CommandSaveResponse.class); }
    @Override public CommandSaveResponse updateDocker(CommandDockerRequest aRequest)             { return post("/control-plane/api/command/update/docker", aRequest, CommandSaveResponse.class); }

    private <T> T post(String aPath, Object aRequest, Class<T> aResponseClass) {
        String url  = baseUrl + aPath;
        String json = gson.toJson(aRequest);

        LOG.debug(">> POST {} ...", url);

        HttpRequest request = HttpRequest.builder()
                .url(url)
                .method(HttpMethod.POST)
                .headers(new HttpHeaders(
                        asList(
                                  new HttpHeader("Authorization", "Bearer " + bearerToken)
                                , new HttpHeader("Content-type", "application/json")
                        )
                ))
                .body(json.getBytes(UTF_8))
                .build();


        HttpResponse response;
        try {
            response = httpClient.send(request, requestParameters);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot send task to " + url, e);
        }

        LOG.debug("<< POST {} {}", url, response.getStatusCode());

        String jsonResponse = new String(response.getBody(), UTF_8);
        if (response.getStatusCode() != 200) {
            throw new IllegalStateException("Bad response status " + response.getStatusCode() + " " + jsonResponse);
        }

        return gson.fromJson(jsonResponse, aResponseClass);
    }


}
