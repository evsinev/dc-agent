package com.payneteasy.dcagent.core.config.model.docker.volumes;

import com.google.gson.annotations.JsonAdapter;

/**
 * How a {@code dirConfig} directory is synchronized with the host. Missing in dc-docker.yml means
 * {@link #OVERLAY}.
 */
@JsonAdapter(TDirSyncAdapter.class)
public enum TDirSync {

    /** Add new files, overwrite changed ones, never delete. */
    OVERLAY,

    /** Like {@link #OVERLAY}, and also delete what is under {@code source} but not in the task, except {@code keep}. */
    MIRROR
}
