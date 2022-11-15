package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.BoundVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.payneteasy.dcagent.core.util.SaveList.safeList;

public class BoundVariablesResolver {
    private static final Logger LOG = LoggerFactory.getLogger( BoundVariablesResolver.class );

    public List<BoundVariable> mergeVariables(List<BoundVariable> aList, Map<String, String> aMap) {
        if(aMap == null) {
            return aList;
        }

        List<BoundVariable> ret = new ArrayList<>(safeList(aList));
        for (Map.Entry<String, String> entry : aMap.entrySet()) {
            ret.add(BoundVariable.builder()
                    .name(entry.getKey())
                    .value(entry.getValue())
                    .build());
        }

        LOG.debug("Merged bound variables:");
        for (Map.Entry<String, String> entry : aMap.entrySet()) {
            LOG.debug("    {} = {}", entry.getKey(), entry.getValue());
        }
        return ret;
    }

}
