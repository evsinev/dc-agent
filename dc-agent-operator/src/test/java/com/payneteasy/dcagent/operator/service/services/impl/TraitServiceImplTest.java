package com.payneteasy.dcagent.operator.service.services.impl;


import org.junit.Test;

import java.util.Date;

import static com.payneteasy.dcagent.operator.service.services.impl.HostServiceItemMapper.formatAge;
import static org.junit.Assert.assertEquals;

public class TraitServiceImplTest {

    @Test
    public void format_age() {
        assertEquals("0s", formatAge(1_718_062_368_000L, new Date(1_718_062_368_000L)));
        assertEquals("1s", formatAge(1_718_062_368_000L, new Date(1_718_062_367_000L)));
        assertEquals("1m", formatAge(1_718_062_368_000L, new Date(1_718_062_308_000L)));
        assertEquals("2m48s", formatAge(1_718_062_368_000L, new Date(1_718_062_200_000L)));
        assertEquals("6M30D", formatAge(1_718_062_368_000L, new Date(1700002368_000L)));
    }
}