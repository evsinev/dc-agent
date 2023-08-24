package com.payneteasy.dcagent.admin.service.impl;

import com.google.gson.Gson;
import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.admin.dao.IAuthDao;
import com.payneteasy.dcagent.admin.dao.model.TUser;
import com.payneteasy.dcagent.admin.error.BadClientException;
import com.payneteasy.dcagent.admin.error.BadParameterException;
import com.payneteasy.dcagent.admin.error.BadUserException;
import com.payneteasy.dcagent.admin.service.tokens.ITokensService;
import com.payneteasy.dcagent.admin.service.IUiAdminService;
import com.payneteasy.dcagent.admin.service.messages.*;
import com.payneteasy.dcagent.admin.service.messages.save.FetchUrlConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.messages.save.JarConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.messages.save.SaveArtifactConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.messages.save.ZipArchiveConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.tokens.model.AccessToken;
import com.payneteasy.dcagent.admin.service.tokens.model.CreateTokenParameters;
import com.payneteasy.dcagent.admin.service.tokens.model.RefreshAccessToken;
import com.payneteasy.dcagent.admin.service.tokens.model.RefreshTokenParameters;
import com.payneteasy.dcagent.core.config.model.TJarConfig;
import com.payneteasy.dcagent.core.util.GsonReader;
import com.payneteasy.dcagent.core.util.Strings;
import com.payneteasy.dcagent.util.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;

public class UiAdminServiceImpl implements IUiAdminService {

    private static final Logger LOG = LoggerFactory.getLogger(UiAdminServiceImpl.class);

    private final Gson            gson;
    private final File            configDir;
    private final File            optDir;
    private final AdminListAction adminListAction;
    private final IAuthDao        authDao;
    private final ITokensService  tokensService;

    public UiAdminServiceImpl(Gson aGson, File aConfigDir, File aOptDir, IAuthDao aAuthDao, ITokensService aTokensService) {
        gson            = aGson;
        configDir       = aConfigDir;
        optDir          = aOptDir;
        authDao         = aAuthDao;
        tokensService   = aTokensService;
        adminListAction = new AdminListAction(aGson, aConfigDir);
    }

    @Override
    public TaskListResponse listTasks(VoidRequest aVoid) {
        return adminListAction.listTasks();
    }

    @Override
    public TaskViewJarResponse getJarTask(TaskViewRequest aRequest) {
        return TaskViewJarResponse.builder()
                .taskName(aRequest.getTaskName())
                .jarConfig(new GsonReader(gson)
                        .loadFile(new File(configDir, aRequest.getTaskName() + ".json"), TJarConfig.class)
                        .toBuilder()
                        .apiKeys(Maps.singleMap("gitlab", "***"))
                        .build()
                )
                .build();
    }

    @Override
    public void saveFetchUrl(FetchUrlConfigSaveRequest aRequest) {

    }

    @Override
    public JarConfigSaveRequest saveJar(JarConfigSaveRequest aRequest) {
        return JarConfigSaveRequest.builder().build();
    }

    @Override
    public void saveArtifact(SaveArtifactConfigSaveRequest aRequest) {

    }

    @Override
    public void saveArchive(ZipArchiveConfigSaveRequest aRequest) {

    }

    @Override
    public TokenResponse token(TokenRequest aRequest) {
//        authDao.checkClient(aRequest.getClientId(), aRequest.getClientSecret()).orElseThrow(BadClientException::new);
        if(!aRequest.getClientId().equals("client-1") && !aRequest.getClientSecret().equals("client-secret-1")) {
            throw new BadClientException();
        }

        if(!aRequest.getUsername().equals("user-1") && !aRequest.getPassword().equals("password-1")) {
            throw new BadUserException();
        }
//        TUser user = authDao.checkUsernameAndPassword(aRequest.getUsername(), aRequest.getPassword()).orElseThrow(BadUserException::new);

        RefreshAccessToken tokens = tokensService.createTokens(
                CreateTokenParameters.builder()
                        .clientId     ( aRequest.getClientId()     )
                        .clientSecret ( aRequest.getClientSecret() )
                        .username     ( aRequest.getUsername() )
                        .password     ( aRequest.getPassword() )
                        .build()
        );

        return TokenResponse.builder()
                .accessToken(tokens.getAccessToken())
                .refreshToken(tokens.getRefreshToken())
                .build();
    }

    @Override
    public RefreshResponse refresh(RefreshRequest aRequest) {
        if(Strings.isEmpty(aRequest.getRefreshToken())) {
            throw new BadParameterException("refreshToken is empty");
        }

        if(Strings.isEmpty(aRequest.getClientId())) {
            throw new BadParameterException("clientId is empty");
        }

        if(Strings.isEmpty(aRequest.getClientSecret())) {
            throw new BadParameterException("clientSecret is empty");
        }

        AccessToken accessToken = tokensService.createAccessTokenFromRefresh(RefreshTokenParameters.builder()
                .refreshTokenValue(aRequest.getRefreshToken())
                .build());
        return RefreshResponse.builder()
                .accessToken(accessToken.getAccessTokenValue())
                .build();
    }

    @Override
    public UserInfoResponse userInfo(UserInfoRequest aRequest) {
//        TUser user = authDao.findUser(aRequest)
        return UserInfoResponse.builder()
                .username("user1")
                .actions(Arrays.asList("ACTION_TASK_LIST", "ACTION_TASK_VIEW", "ACTION_TASK_EDIT", "ACTION_TASK_DELETE"))
                .build();
    }
}
