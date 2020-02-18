package com.payneteasy.dcagent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

/**
 * Deals with URI
 */
public class PathParameters {

    private final List<String> params;
    private final String       uri;

    public PathParameters(String aUri) {
        uri = aUri;
        List<String>    list = new ArrayList<>();
        StringTokenizer st   = new StringTokenizer(aUri, "/");
        while (st.hasMoreTokens()) {
            list.add(st.nextToken());
        }
        params = list;
    }

    public String getLast() {
        return getFromLast(0);
    }

    public String getLastButOne() {
        return getFromLast(1);
    }

    public String getSecondFromLast() {
        return getFromLast(2);
    }

    public String getFromLast(int aPosition) {
        try {
            return params.get(params.size() - 1 - aPosition);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to get parameter -" + aPosition + " from " + uri + " " + params);
        }
    }
}
