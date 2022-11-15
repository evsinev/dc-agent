package com.payneteasy.dcagent.util;

import java.util.HashMap;
import java.util.Map;

public class Maps {

    public static <K, V> Map<K, V> singleMap(K aKey, V aValue) {
        HashMap<K, V> map = new HashMap<>();
        map.put(aKey, aValue);
        return map;
    }
}
