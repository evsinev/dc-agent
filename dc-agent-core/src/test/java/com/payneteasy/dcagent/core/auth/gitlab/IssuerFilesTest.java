package com.payneteasy.dcagent.core.auth.gitlab;

import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class IssuerFilesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final TestTokens TOKENS = new TestTokens(2048);
    private static final TestTokens SHORT  = new TestTokens(1024);

    private static void refused(String aFile, String aReason) {
        assertThatThrownBy(() -> IssuerFiles.parse("test", JsonParser.parseString(aFile).getAsJsonObject()))
                .isInstanceOf(JwtRejectedException.class).hasMessageContaining(aReason);
    }

    @Test
    public void a_good_file_loads_with_its_keys() throws Exception {
        Path dir = tmp.newFolder("keys").toPath();
        Files.writeString(dir.resolve("code-clubber-me.json"), TOKENS.issuerFile("2026-11-21T03:00:00Z"), UTF_8);

        IssuerKeys keys = IssuerFiles.load(dir, "code-clubber-me");

        assertThat(keys.issuer()).isEqualTo(TestTokens.ISSUER);
        assertThat(keys.keys()).containsOnlyKeys(TestTokens.KID);
        assertThat(keys.fetchedAt()).hasToString("2026-11-21T03:00:00Z");
    }

    @Test
    public void the_file_is_strict() {
        refused(TOKENS.issuerFile(null).replace("{\"issuer\"", "{\"extra\":1,\"issuer\""), "unknown field");
        refused(TOKENS.issuerFile(null).replace("https://gitlab.test/git", "http://gitlab.test/git"), "https URL");
        refused(TOKENS.issuerFile("yesterday"), "fetchedAt");
        refused(TOKENS.issuerFile(null).replace("[" + TOKENS.jwk(TestTokens.KID) + "]", "[" + TOKENS.jwk("a") + "," + TOKENS.jwk("a") + "]"), "twice");
        refused(TOKENS.issuerFile(null).replace("\"RS256\"", "\"RS512\""), "not for RS256");
        refused(TOKENS.issuerFile(null).replace("\"sig\"", "\"enc\""), "not a signing key");
        refused(TOKENS.issuerFile(null).replace("\"RSA\"", "\"EC\""), "not RSA");
        refused(TOKENS.issuerFile(null).replace(",\"alg\":\"RS256\"", ""), "(alg)");
        refused(TOKENS.issuerFile(null).replace("\"use\":\"sig\"", "\"ues\":\"enc\""), "unknown field");
        refused(TOKENS.issuerFile(null).replace("\"jwks\":{\"keys\"", "\"jwks\":{\"x\":1,\"keys\""), "\"jwks\" must be");
        refused(SHORT.issuerFile(null), "shorter than 2048");
        refused(TOKENS.issuerFile(null).replace("[" + TOKENS.jwk(TestTokens.KID) + "]", "[]"), "no keys");
    }

    @Test
    public void missing_linked_or_badly_named_files_are_refused() throws Exception {
        Path dir = tmp.newFolder("keys").toPath();
        assertThatThrownBy(() -> IssuerFiles.load(dir, "absent")).hasMessageContaining("missing");
        Path real = tmp.newFile("real.json").toPath();
        Files.writeString(real, TOKENS.issuerFile(null), UTF_8);
        Files.createSymbolicLink(dir.resolve("linked.json"), real);
        assertThatThrownBy(() -> IssuerFiles.load(dir, "linked")).hasMessageContaining("not a regular file");
        assertThatThrownBy(() -> IssuerFiles.load(dir, "../real")).hasMessageContaining("invalid issuer name");
        Files.createDirectory(dir.resolve("folder.json"));
        assertThatThrownBy(() -> IssuerFiles.load(dir, "folder")).hasMessageContaining("not a regular file");
    }

    @Test
    public void a_file_over_the_limit_is_refused_without_reading_it_all() throws Exception {
        Path dir = tmp.newFolder("keys").toPath();
        Files.write(dir.resolve("big.json"), new byte[(int) IssuerFiles.MAX_BYTES + 1]);
        assertThatThrownBy(() -> IssuerFiles.load(dir, "big")).hasMessageContaining("larger than");
    }
}
