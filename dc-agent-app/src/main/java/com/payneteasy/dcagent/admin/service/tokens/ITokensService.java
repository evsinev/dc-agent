package com.payneteasy.dcagent.admin.service.tokens;

import com.payneteasy.dcagent.admin.service.tokens.model.*;

public interface ITokensService {

    RefreshAccessToken createTokens(CreateTokenParameters aParameters);

    AccessToken createAccessTokenFromRefresh(RefreshTokenParameters aRefresh);

    void checkAccessToken(AccessTokenParameters aParameters);
}
