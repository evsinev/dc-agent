package com.payneteasy.dcagent.core.config;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CommandNamesTest {

    @Test
    public void accepts_letters_digits_dot_underscore_dash_of_any_length() {
        assertThat(CommandNames.isValid("billing")).isTrue();
        assertThat(CommandNames.isValid("_app")).isTrue();
        assertThat(CommandNames.isValid(".app")).isTrue();
        assertThat(CommandNames.isValid("fetch-url")).isTrue();
        assertThat(CommandNames.isValid("a.b_c-D9")).isTrue();
        assertThat(CommandNames.isValid("a".repeat(200))).isTrue();
    }

    @Test
    public void rejects_empty_traversal_separators_and_other_characters() {
        assertThat(CommandNames.isValid(null)).isFalse();
        assertThat(CommandNames.isValid("")).isFalse();
        assertThat(CommandNames.isValid("..")).isFalse();
        assertThat(CommandNames.isValid("a..b")).isFalse();
        assertThat(CommandNames.isValid("a/b")).isFalse();
        assertThat(CommandNames.isValid("a\\b")).isFalse();
        assertThat(CommandNames.isValid("a b")).isFalse();
        assertThat(CommandNames.isValid("a%2Fb")).isFalse();
        assertThat(CommandNames.isValid("app\n")).isFalse();
    }
}
