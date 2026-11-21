package com.payneteasy.dcagent.jetty;

import com.payneteasy.dcagent.core.config.model.IApiKeys;

import javax.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.time.Clock;

/** Tests in other packages: CheckApiKey's reason for a request. */
public final class CheckApiKeyAccess {

    private final CheckApiKey check;

    public CheckApiKeyAccess(Path aKeysDir, Clock aClock) {
        check = new CheckApiKey(aKeysDir, aClock);
    }

    public String reason(HttpServletRequest aRequest, IApiKeys aKeys) {
        return check.rejectReason(aRequest, aKeys, "test");
    }
}
