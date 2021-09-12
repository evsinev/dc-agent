package com.payneteasy.dcagent.modules.jar;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;

public class WaitUrlCommand {

    private String       waitUrl;
    private Duration     connectTimeout;
    private Duration     readTimeout;
    private Duration     waitDuration;
    private ILog         log;
    private ICancelCheck cancelCheck;

    public interface ICancelCheck {
        boolean isCancelled();
    }

    public WaitUrlCommand setWaitUrl(String waitUrl) {
        this.waitUrl = waitUrl;
        return this;
    }

    public WaitUrlCommand setConnectTimeout(Duration connectionTimeout) {
        this.connectTimeout = connectionTimeout;
        return this;
    }

    public WaitUrlCommand setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    public WaitUrlCommand setWaitDuration(Duration waitDuration) {
        this.waitDuration = waitDuration;
        return this;
    }

    public WaitUrlCommand setLog(ILog log) {
        this.log = log;
        return this;
    }

    public WaitUrlCommand setCheck(ICancelCheck check) {
        this.cancelCheck = check;
        return this;
    }

    public void waitForSuccessResponse() throws InterruptedException {

        long endTime = System.currentTimeMillis() + waitDuration.toMillis();

        while ((endTime > System.currentTimeMillis())) {
            if(Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Wait url interrupted");
            }
            if(cancelCheck.isCancelled()) {
                throw new IllegalStateException("Wait url cancelled");
            }
            try {
                if(getResponseCode(waitUrl) == 200) {
                    return;
                }
            } catch (Exception e) {
                log.debug("Error: %s", e.getMessage());
                Thread.sleep(1_000);
            }
        }

        throw new IllegalStateException("Timeout for waiting url with duration " + waitDuration);
    }


    private int getResponseCode(String aUrl) throws IOException {
        log.debug("Fetching %s with timeouts %s/%s ...", aUrl, connectTimeout, readTimeout);

        URL               url = new URL(aUrl);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout((int) connectTimeout.toMillis());
        con.setReadTimeout((int) readTimeout.toMillis());

        return con.getResponseCode();
    }
}
