package com.payneteasy.dcagent.admin.service.tokens.impl;

import com.payneteasy.dcagent.admin.service.tokens.model.CreateTokenParameters;
import com.payneteasy.dcagent.admin.service.tokens.model.RefreshAccessToken;

public class CreateTokensAction {

    public RefreshAccessToken createTokens(CreateTokenParameters aParameters) {
        return new RefreshAccessToken(
                "refresh-token-1", System.currentTimeMillis() + ""
        );
    }

}
