package com.payneteasy.dcagent.admin.service.tokens.impl;

import com.payneteasy.dcagent.admin.error.BadParameterException;
import com.payneteasy.dcagent.admin.error.BadRefreshTokenException;
import com.payneteasy.dcagent.admin.error.BadTokenException;
import com.payneteasy.dcagent.admin.service.tokens.ITokensService;
import com.payneteasy.dcagent.admin.service.tokens.model.*;
import com.payneteasy.dcagent.core.util.Strings;

import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

public class TokensServiceImpl implements ITokensService {

    @Override
    public RefreshAccessToken createTokens(CreateTokenParameters aParameters) {
        return new CreateTokensAction().createTokens(aParameters);
    }

    @Override
    public AccessToken createAccessTokenFromRefresh(RefreshTokenParameters aRefresh) {
        if(!aRefresh.getRefreshTokenValue().equals("refresh-token-1")) {
            throw new BadRefreshTokenException("Refresh token should be equal to 'refresh-token-1'");
        }
        return new AccessToken(System.currentTimeMillis() + "");
    }

    @Override
    public void checkAccessToken(AccessTokenParameters aParameters) {
        long value = Long.parseLong(aParameters.getAccessTokenValue());
        if(value < System.currentTimeMillis()) {
            throw new BadTokenException("Access token is expired ");
        }
    }

    private String createRandomString(String aPrefix) {
        byte[] randomBytes = new byte[64];
        ThreadLocalRandom.current().nextBytes(randomBytes);
        return aPrefix + "-1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

}
