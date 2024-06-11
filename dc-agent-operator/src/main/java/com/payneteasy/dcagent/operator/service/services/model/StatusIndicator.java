package com.payneteasy.dcagent.operator.service.services.model;

import com.google.gson.annotations.SerializedName;

public enum StatusIndicator {
    @SerializedName("error")       ERROR,
    @SerializedName("warning")     WARNING,
    @SerializedName("success")     SUCCESS,
    @SerializedName("info")        INFO,
    @SerializedName("stopped")     STOPPED,
    @SerializedName("pending")     PENDING,
    @SerializedName("in-progress") IN_PROGRESS,
    @SerializedName("loading")     LOADING,
}
