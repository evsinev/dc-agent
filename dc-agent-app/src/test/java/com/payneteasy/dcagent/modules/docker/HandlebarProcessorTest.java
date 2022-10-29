package com.payneteasy.dcagent.modules.docker;

import com.payneteasy.dcagent.config.model.docker.BoundVariable;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class HandlebarProcessorTest {

    @Test
    public void processTemplate() {
        HandlebarProcessor processor = new HandlebarProcessor();
        String text = processor.processTemplate("hello {{ NAME_1 }}", Arrays.asList(
                BoundVariable.builder()
                        .name("NAME_1")
                        .value("value_1")
                        .build()
                , BoundVariable.builder()
                        .name("NAME_2")
                        .value("value_2")
                        .build()
        ));
        Assert.assertEquals("hello value_1", text);
    }
}