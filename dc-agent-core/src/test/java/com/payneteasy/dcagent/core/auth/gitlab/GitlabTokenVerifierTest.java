package com.payneteasy.dcagent.core.auth.gitlab;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every refusal next to a passing twin that differs only in the checked part. */
public class GitlabTokenVerifierTest {

    private static final TestTokens TOKENS = new TestTokens(2048);
    private static final TestTokens OTHER  = new TestTokens(2048);
    private static final long       NOW    = 1_795_215_862L;
    private static final Duration   AGE    = Duration.ofMinutes(60);

    private static IssuerKeys issuer() throws Exception {
        return IssuerFiles.parse("test", JsonParser.parseString(TOKENS.issuerFile(null)).getAsJsonObject());
    }

    private static JsonObject verify(String aToken) throws Exception {
        return GitlabTokenVerifier.verify(aToken, issuer(), "dc-agent:test", AGE, Instant.ofEpochSecond(NOW));
    }

    private static void refused(String aToken, String aReason) {
        assertThatThrownBy(() -> verify(aToken)).isInstanceOf(JwtRejectedException.class).hasMessageContaining(aReason);
    }

    private static String good() {
        return TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW - 60, ""));
    }

    @Test
    public void a_good_token_gives_its_claims() throws Exception {
        assertThat(verify(good()).get("project_id").getAsString()).isEqualTo("913");
    }

    @Test
    public void only_rs256_with_a_known_kid_and_no_key_bearing_headers() {
        refused(TOKENS.sign("{\"kid\":\"test-kid\",\"alg\":\"HS256\"}", TestTokens.payload(NOW, "")), "alg is not RS256");
        refused(TOKENS.sign("{\"kid\":\"test-kid\",\"alg\":\"none\"}", TestTokens.payload(NOW, "")), "alg is not RS256");
        refused(TOKENS.sign("{\"alg\":\"RS256\"}", TestTokens.payload(NOW, "")), "no kid");
        refused(TOKENS.sign("{\"kid\":\"other\",\"alg\":\"RS256\"}", TestTokens.payload(NOW, "")), "unknown kid");
        for (String header : new String[]{"jku", "x5u", "jwk", "x5c", "crit"}) {
            refused(TOKENS.sign("{\"kid\":\"test-kid\",\"alg\":\"RS256\",\"" + header + "\":\"x\"}", TestTokens.payload(NOW, "")), "header " + header);
        }
    }

    @Test
    public void the_signature_covers_header_and_payload_with_the_issuer_key() {
        String good = good();
        String[] parts = good.split("\\.");
        String forged = parts[0] + "." + TestTokens.b64(TestTokens.payload(NOW - 60, ",\"x\":1")) + "." + parts[2];
        refused(forged, "bad signature");
        refused(OTHER.sign(TestTokens.header(), TestTokens.payload(NOW - 60, "")), "bad signature");
    }

    @Test
    public void iss_and_aud_must_match() {
        refused(TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW, "").replace("https://gitlab.test/git", "https://evil.test/git")), "iss is not");
        refused(TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW, "").replace("\"dc-agent:test\"", "\"dc-agent:other\"")), "aud is not");
        refused(TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW, "").replace("\"dc-agent:test\"", "[\"dc-agent:test\",1]")), "aud is not");
    }

    @Test
    public void aud_may_be_a_list_holding_the_audience() throws Exception {
        assertThat(verify(TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW - 60, "").replace("\"dc-agent:test\"", "[\"x\",\"dc-agent:test\"]"))))
                .isNotNull();
    }

    @Test
    public void time_claims_are_required_integers_checked_with_a_minute_of_skew() throws Exception {
        // expired a minute and a half ago — refused; half a minute ago — within the skew
        refused(TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW - 3600 - 90, "")), "expired");
        assertThat(GitlabTokenVerifier.verify(TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW - 3600 - 30, "")), issuer(),
                "dc-agent:test", Duration.ofHours(2), Instant.ofEpochSecond(NOW))).isNotNull();
        refused(TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW + 120, "")), "not valid yet");
        refused(TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW - 60, "").replace("\"exp\":" + (NOW + 3540), "\"exp\":" + (NOW + 90_000))), "lifetime");
        refused(TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW - 60, "").replace("\"exp\":" + (NOW + 3540), "\"exp\":1.79522e9")), "exp is missing or not an integer");
        refused(TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW - 60, "").replace("\"iat\":" + (NOW - 60), "\"iat\":\"" + (NOW - 60) + "\"")), "iat is missing or not an integer");
    }

    @Test
    public void older_than_max_token_age_is_refused_whatever_exp_says() throws Exception {
        String token = TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW - 1800, ""));
        assertThat(GitlabTokenVerifier.verify(token, issuer(), "dc-agent:test", Duration.ofMinutes(31), Instant.ofEpochSecond(NOW))).isNotNull();
        assertThatThrownBy(() -> GitlabTokenVerifier.verify(token, issuer(), "dc-agent:test", Duration.ofMinutes(29), Instant.ofEpochSecond(NOW)))
                .hasMessageContaining("older than maxTokenAge");
    }

    @Test
    public void a_duplicate_claim_is_refused_not_resolved() {
        refused(TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW - 60, ",\"project_id\":\"914\"")), "duplicate name");
        refused(TOKENS.sign(TestTokens.header(), TestTokens.payload(NOW - 60, ",\"proj\\u0065ct_id\":\"914\"")), "duplicate name");
    }

    @Test
    public void only_canonical_compact_tokens() {
        String good = good();
        refused(good + ".x", "not three parts");
        refused(good.replaceFirst("\\.", "=."), "not canonical base64url");
        String[] parts = good.split("\\.");
        // the last character of the signature with its unused low bits set: decodes the same, not canonical
        char last = parts[2].charAt(parts[2].length() - 1);
        String bent = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 1) + (char) (last + 1);
        assertThatThrownBy(() -> verify(bent)).isInstanceOf(JwtRejectedException.class);
        refused("a".repeat(GitlabTokenVerifier.MAX_TOKEN_BYTES + 1), "longer than");
    }

    @Test
    public void the_payload_must_be_strict_utf8_json() {
        assertThatThrownBy(() -> StrictJson.object(new byte[]{'{', '"', 'a', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'}, "payload"))
                .hasMessageContaining("not valid UTF-8");
        // signed for real, so the payload is reached
        refused(TOKENS.sign(TestTokens.header(), "{\"a\":1,}"), "not strict JSON");
        refused(TOKENS.sign(TestTokens.header(), "[1]"), "not a JSON object");
    }
}
