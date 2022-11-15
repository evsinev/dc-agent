package com.payneteasy.dcagent.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class Enumerations {

    public static <T> List<T> toList(Enumeration<T> aEnums) {
        if(aEnums == null) {
            return Collections.emptyList();
        }

        List<T> list = new ArrayList<>();
        while (aEnums.hasMoreElements()) {
            T value = aEnums.nextElement();
            list.add(value);
        }
        return list;
    }
}
