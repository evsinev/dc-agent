package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.config.model.TZipArchiveVersionConfig;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.config.service.impl.ConfigServiceImpl;
import com.payneteasy.dcagent.core.util.gson.Gsons;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ZipArchiveVersionSettingsTest {

    private static final Duration IDLE = Duration.ofMinutes(10);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static TZipArchiveVersionConfig.TZipArchiveVersionConfigBuilder base() {
        return TZipArchiveVersionConfig.builder()
                .type(TaskType.ZIP_ARCHIVE_VERSION)
                .apiKeys(Map.of("key", "ci"))
                .dir("/opt/app/bundles")
                .versionFile("/opt/app/bundles/current")
                .reloadUrl("http://127.0.0.1:8080/bundle/reload?version=${version}");
    }

    private static ZipArchiveVersionSettings settings(UnaryOperator<TZipArchiveVersionConfig.TZipArchiveVersionConfigBuilder> aChange) {
        return ZipArchiveVersionSettings.from("app-bundle", aChange.apply(base()).build(), IDLE);
    }

    private static void refused(String aField, UnaryOperator<TZipArchiveVersionConfig.TZipArchiveVersionConfigBuilder> aChange) {
        assertThatThrownBy(() -> settings(aChange))
                .isInstanceOfSatisfying(ConfigValueException.class, e -> {
                    assertThat(e.getField()).isEqualTo(aField);
                    assertThat(e.getHttpCode()).isEqualTo(500);
                    assertThat(e.getMessage()).startsWith("config app-bundle: field " + aField + ": ");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    public void defaults_are_finite() {
        ZipArchiveVersionSettings settings = settings(b -> b);

        assertThat(settings.dir()).isEqualTo(Path.of("/opt/app/bundles"));
        assertThat(settings.pointerName()).isEqualTo("current");
        assertThat(settings.versionFile()).isEqualTo(Path.of("/opt/app/bundles/current"));
        assertThat(settings.maxUploadBytes()).isEqualTo(100L * 1024 * 1024);
        assertThat(settings.maxBytes()).isEqualTo(500L * 1024 * 1024);
        assertThat(settings.maxEntries()).isEqualTo(10_000);
        assertThat(settings.waitTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(settings.versionPattern()).isNull();
    }

    @Test
    public void readable_values() {
        ZipArchiveVersionSettings settings = settings(b -> b.maxUploadBytes("50mb").maxBytes("200MB")
                .maxEntries("500").waitTimeout("6m"));

        assertThat(settings.maxUploadBytes()).isEqualTo(50L * 1024 * 1024);
        assertThat(settings.maxBytes()).isEqualTo(200L * 1024 * 1024);
        assertThat(settings.maxEntries()).isEqualTo(500);
        assertThat(settings.waitTimeout()).isEqualTo(Duration.ofMinutes(6));
    }

    @Test
    public void bad_values_name_the_field() {
        refused("maxUploadBytes", b -> b.maxUploadBytes("5x"));
        refused("maxBytes"      , b -> b.maxBytes("-1"));
        refused("maxBytes"      , b -> b.maxBytes("0"));
        refused("maxEntries"    , b -> b.maxEntries("1.5k"));
        refused("maxEntries"    , b -> b.maxEntries("99999999999"));
        refused("waitTimeout"   , b -> b.waitTimeout("1.5m"));
        refused("waitTimeout"   , b -> b.waitTimeout("-5s"));
        refused("waitTimeout"   , b -> b.waitTimeout("0s"));
        refused("versionPattern", b -> b.versionPattern("[unclosed"));
    }

    @Test
    public void wait_timeout_must_fit_in_the_idle_timeout_with_the_lock_wait() {
        assertThat(settings(b -> b.waitTimeout("8m")).waitTimeout()).isEqualTo(Duration.ofMinutes(8));
        refused("waitTimeout", b -> b.waitTimeout("9m"));
        refused("waitTimeout", b -> b.waitTimeout("1h"));
    }

    @Test
    public void a_huge_wait_timeout_is_a_field_error_not_an_overflow() {
        refused("waitTimeout", b -> b.waitTimeout("9223372036854775807s"));
    }

    @Test
    public void a_version_the_reload_request_cannot_carry_is_refused_before_the_disk() {
        // v0 (the load probe) is a valid host label, v1_rc is not
        ZipArchiveVersionSettings settings = settings(b -> b.reloadUrl("http://${version}.internal/reload"));

        assertThat(settings.versionProblem("v0")).isNull();
        assertThat(settings.versionProblem("v1_rc")).isEqualTo("the reload request of the command cannot be built for this version");
    }

    @Test
    public void dir_and_version_file() {
        refused("dir"        , b -> b.dir(null));
        refused("dir"        , b -> b.dir("relative/bundles"));
        refused("dir"        , b -> b.dir("/"));
        refused("versionFile", b -> b.versionFile(null));
        refused("versionFile", b -> b.versionFile("current"));
        refused("versionFile", b -> b.versionFile("/opt/app/current"));
        refused("versionFile", b -> b.versionFile("/opt/app/bundles/sub/current"));
        refused("versionFile", b -> b.versionFile("/opt/app/bundles/.current"));
        refused("versionFile", b -> b.versionFile("/opt/app/bundles/-current"));
        // normalised before the comparison
        assertThat(settings(b -> b.versionFile("/opt/app/bundles/./current")).pointerName()).isEqualTo("current");
        assertThat(settings(b -> b.dir("/opt/app/bundles/")).dir()).isEqualTo(Path.of("/opt/app/bundles"));
    }

    @Test
    public void version_names_are_checked_in_code_even_with_a_permissive_pattern() {
        ZipArchiveVersionSettings settings = settings(b -> b.versionPattern(".*"));

        assertThat(settings.versionProblem("v0.4.1")).isNull();
        for (String bad : new String[]{"a/b", "a\\b", ".", "..", ".v1", "", "current"}) {
            assertThat(settings.versionProblem(bad)).as(bad).isNotNull();
        }
    }

    @Test
    public void version_pattern_matches_the_whole_name() {
        ZipArchiveVersionSettings settings = settings(b -> b.versionPattern("v[0-9]+\\.[0-9]+\\.[0-9]+"));

        assertThat(settings.versionProblem("v0.4.1")).isNull();
        assertThat(settings.versionProblem("v0.4.1-rc")).isNotNull();
        assertThat(settings.versionProblem("xv0.4.1")).isNotNull();
    }

    @Test
    public void reload_url_and_headers() {
        refused("reloadUrl", b -> b.reloadUrl(null));
        refused("reloadUrl", b -> b.reloadUrl("ftp://host/reload"));
        refused("reloadUrl", b -> b.reloadUrl("http:///no-host"));
        refused("reloadUrl", b -> b.reloadUrl("http://host/a b"));
        refused("reloadUrl", b -> b.reloadUrl("/relative"));

        refused("reloadHeaders", b -> b.reloadHeaders(headers("Bad Name", "x")));
        refused("reloadHeaders", b -> b.reloadHeaders(headers("X-A", null)));
        refused("reloadHeaders", b -> b.reloadHeaders(headers("X-A", "line\r\nInjected: 1")));
        // restricted by the JDK client — a config error at load, not after the pointer has moved
        refused("reloadHeaders", b -> b.reloadHeaders(headers("Host", "evil")));
        refused("reloadHeaders", b -> b.reloadHeaders(headers("Content-Length", "0")));
    }

    @Test
    public void a_refused_header_value_never_reaches_the_message() {
        for (String value : new String[]{"Bearer SECRET\u000b", "Bearer SECRET\u0000", "Bearer SECRETé"}) {
            assertThatThrownBy(() -> settings(b -> b.reloadHeaders(headers("Authorization", value))))
                    .isInstanceOf(ConfigValueException.class)
                    .hasMessageNotContaining("SECRET")
                    .hasNoCause();
        }
        assertThatThrownBy(() -> settings(b -> b.reloadHeaders(headers("Host", "SECRET"))))
                .hasMessageNotContaining("SECRET");
        assertThat(settings(b -> b.reloadHeaders(headers("Authorization", "Bearer SECRET"))).toString())
                .doesNotContain("SECRET")
                .contains("Authorization");
    }

    @Test
    public void reload_request_substitutes_the_version() {
        ZipArchiveVersionSettings settings = settings(b -> b
                .reloadHeaders(headers("Authorization", "Bearer token"))
                .reloadBody("{\"version\":\"${version}\"}"));

        HttpRequest request = assertDoesNotThrow(() -> settings.reloadRequest("v0.4.1"));

        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo("http://127.0.0.1:8080/bundle/reload?version=v0.4.1");
        assertThat(request.headers().firstValue("Authorization")).hasValue("Bearer token");
        assertThat(request.timeout()).hasValue(Duration.ofMinutes(5));
        assertThat(request.bodyPublisher().orElseThrow().contentLength()).isEqualTo("{\"version\":\"v0.4.1\"}".length());
    }

    @Test
    public void loads_yaml_with_numbers_and_readable_values() throws Exception {
        Path config = tmp.newFolder("config").toPath();
        Files.writeString(config.resolve("bundle.yml"), String.join("\n",
                "type: ZIP_ARCHIVE_VERSION",
                "apiKeys:",
                "  KEY: ci",
                "dir: /opt/app/bundles",
                "versionFile: /opt/app/bundles/current",
                "versionPattern: '^v[0-9]+\\.[0-9]+\\.[0-9]+$'",
                "maxUploadBytes: 50mb",
                "maxBytes: 1024",
                "maxEntries: 10k",
                "reloadUrl: http://127.0.0.1:8080/bundle/reload?version=${version}",
                "reloadHeaders:",
                "  Authorization: Bearer token",
                "waitTimeout: 6m",
                ""));

        TZipArchiveVersionConfig raw = new ConfigServiceImpl(config.toFile(), Gsons.PRETTY_GSON).getZipArchiveVersionConfig("bundle");
        ZipArchiveVersionSettings settings = ZipArchiveVersionSettings.from("bundle", raw, IDLE);

        assertThat(raw.getType()).isEqualTo(TaskType.ZIP_ARCHIVE_VERSION);
        assertThat(settings.maxUploadBytes()).isEqualTo(50L * 1024 * 1024);
        assertThat(settings.maxBytes()).isEqualTo(1024);
        assertThat(settings.maxEntries()).isEqualTo(10_000);
        assertThat(settings.waitTimeout()).isEqualTo(Duration.ofMinutes(6));
        assertThat(settings.versionProblem("v1.2.3")).isNull();
        assertThat(settings.versionProblem("v1.2")).isNotNull();
    }

    private static Map<String, String> headers(String aName, String aValue) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(aName, aValue);
        return map;
    }

    private static <T> T assertDoesNotThrow(java.util.concurrent.Callable<T> aCall) {
        try {
            return aCall.call();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
