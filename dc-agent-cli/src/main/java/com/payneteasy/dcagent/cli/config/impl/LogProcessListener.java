package com.payneteasy.dcagent.cli.config.impl;

import com.payneteasy.osprocess.api.IProcessListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class LogProcessListener implements IProcessListener {

    private static final Logger LOG = LoggerFactory.getLogger( LogProcessListener.class );

    @Override
    public void onStandardOut(String s) {
        LOG.info("{}", s);
    }

    @Override
    public void onErrorOut(String s) {
        LOG.error("{}", s);
    }

    @Override
    public void onExit(int i) {

    }

    @Override
    public void onReadError(IOException e) {
        LOG.error("Cannot read", e);
    }
}
