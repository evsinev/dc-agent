package com.payneteasy.dcagent.core.config.validation;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ApiKeyHashTest {

    private static final String KEY = "the-ci-key-0123456789abcdef0123456789";

    @Test
    public void a_key_is_stored_as_sha256_lowercase_hex() {
        // printf %s the-ci-key-0123456789abcdef0123456789 | sha256sum
        assertThat(ApiKeyHash.hash(KEY)).matches("sha256:[0-9a-f]{64}");
        assertThat(ApiKeyHash.hash("abc")).isEqualTo("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    public void the_presented_key_matches_its_hash_and_nothing_else() {
        String stored = ApiKeyHash.hash(KEY);

        assertThat(ApiKeyHash.matches(KEY, List.of(stored))).isTrue();
        // the hash itself, taken from a stolen config, is not a key
        assertThat(ApiKeyHash.matches(stored, List.of(stored))).isFalse();
        // a plaintext entry never matches, even the same text
        assertThat(ApiKeyHash.matches(KEY, List.of(KEY))).isFalse();
        assertThat(ApiKeyHash.matches("other-key-0123456789abcdef01234567", List.of(stored))).isFalse();
    }

    @Test
    public void the_prefix_is_reserved_in_any_case_and_only_the_exact_form_is_a_hash() {
        String stored = ApiKeyHash.hash(KEY);

        assertThat(ApiKeyHash.isHashed(stored)).isTrue();
        assertThat(ApiKeyHash.isHashed(stored.toUpperCase())).isFalse();
        assertThat(ApiKeyHash.isHashed("SHA256:" + stored.substring(7))).isFalse();
        assertThat(ApiKeyHash.isHashed(stored + "0")).isFalse();
        assertThat(ApiKeyHash.hasReservedPrefix("SHA256:abc")).isTrue();
        assertThat(ApiKeyHash.isPlaintext("Sha256:abc")).isFalse();
        assertThat(ApiKeyHash.isPlaintext(KEY)).isTrue();
    }

    @Test
    public void the_masked_id_is_the_same_before_and_after_hashing() {
        assertThat(ApiKeyHash.maskedId(KEY)).isEqualTo(ApiKeyHash.maskedId(ApiKeyHash.hash(KEY))).matches("\\*{4}[0-9a-f]{8}");
    }
}
