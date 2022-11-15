package com.payneteasy.dcagent.core.util;

import java.util.Collections;
import java.util.List;

public class SaveList {

    public static  <T> List<T> safeList(List<T> aList) {
        return aList != null ? aList : Collections.emptyList();
    }

}
