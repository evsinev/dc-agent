package com.payneteasy.dcagent.controlplane.service.serviceview;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CycleBufferTest {

    @Test
    public void returns_elements_in_insertion_order_when_not_full() {
        CycleBuffer<String> buffer = new CycleBuffer<>(new String[3]);
        buffer.add("a");
        buffer.add("b");

        assertThat(buffer.toList()).containsExactly("a", "b");
    }

    @Test
    public void skips_unfilled_slots() {
        CycleBuffer<String> buffer = new CycleBuffer<>(new String[5]);
        buffer.add("only");

        assertThat(buffer.toList()).containsExactly("only");
    }

    @Test
    public void keeps_last_n_elements_oldest_first_after_wrap() {
        CycleBuffer<String> buffer = new CycleBuffer<>(new String[2]);
        buffer.add("a");
        buffer.add("b");
        buffer.add("c");

        assertThat(buffer.toList()).containsExactly("b", "c");
    }

    @Test
    public void empty_buffer_returns_empty_list() {
        CycleBuffer<String> buffer = new CycleBuffer<>(new String[3]);

        assertThat(buffer.toList()).isEmpty();
    }

    @Test
    public void does_not_alias_the_source_array() {
        String[] source = {null, null};
        CycleBuffer<String> buffer = new CycleBuffer<>(source);
        source[0] = "mutated";

        assertThat(buffer.toList()).isEmpty();
    }
}
