package com.payneteasy.dcagent.operator.servlet;

import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.operator.service.app.model.TApp;
import com.payneteasy.dcagent.operator.service.errorview.ErrorViewParam;
import com.payneteasy.dcagent.operator.service.errorview.impl.ErrorViewServiceImpl;
import com.payneteasy.freemarker.FreemarkerFactory;
import freemarker.template.Configuration;
import freemarker.template.Version;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FreeMarker comes transitively through the private {@code freemarker-util} (built against 2.3.28);
 * the root pom pins a patched engine. These tests catch the pin being lost and an engine that no
 * longer fits the library's bytecode or the operator's templates.
 *
 * <p>The library sets no output format and the templates are {@code .html}, not {@code .ftlh}, so
 * auto-escaping comes only from each template's {@code [#ftl output_format="HTML"]} header.
 */
public class FreemarkerTemplatesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Hostile text: breaks out of an element, out of a double-quoted attribute, and has an entity. */
    private static final String EVIL         = "<script>alert(1)</script>\" onmouseover=\"x & y";
    private static final String EVIL_ESCAPED = "&lt;script&gt;alert(1)&lt;/script&gt;&quot; onmouseover=&quot;x &amp; y";
    private static final String FTL_HEADER   = "[#ftl output_format=\"HTML\" auto_esc=true]";

    private FreemarkerFactory factory;

    @Before
    public void setUp() throws Exception {
        // empty directory: templates can only come from the classpath, as packaged in dc-operator.jar
        File noLocalTemplates = tmp.newFolder("templates-dir");
        factory = new FreemarkerFactory(noLocalTemplates);
    }

    @Test
    public void engine_is_patched_against_CVE_2026_84939() {
        assertThat(Configuration.getVersion().intValue())
                .as("freemarker %s on the classpath", Configuration.getVersion())
                .isGreaterThanOrEqualTo(new Version(2, 3, 35).intValue());
    }

    @Test
    public void react_index_injects_asset_uris() {
        String html = factory.template("page-react-index.html").instance()
                .add("ASSETS_INDEX_JS_URI" , "/dc-operator/assets/index.js?t=1")
                .add("ASSETS_INDEX_CSS_URI", "/dc-operator/assets/index.css?t=1")
                .createText();

        assertThat(html)
                .contains("src = \"/dc-operator/assets/index.js?t=1\"")
                .contains("href = \"/dc-operator/assets/index.css?t=1\"");
    }

    @Test
    public void error_view_renders_problem() {
        String html = new ErrorViewServiceImpl(factory).getErrorPage(ErrorViewParam.builder()
                .type("NOT_FOUND")
                .title("App not found")
                .description("No app render-1")
                .build());

        assertThat(html)
                .contains(">NOT_FOUND<")
                .contains(">App not found<")
                .contains(">No app render-1<")
                .containsPattern("Error ID <span aria-hidden=\"true\">[0-9a-f-]{36}</span>");
    }

    @Test
    public void error_view_escapes_problem_fields() {
        String html = new ErrorViewServiceImpl(factory).getErrorPage(ErrorViewParam.builder()
                .type(EVIL)
                .title(EVIL)
                .description(EVIL)
                .build());

        assertThat(html).doesNotContain("<script>alert").doesNotContain("\" onmouseover");
        assertThat(countOf(html, EVIL_ESCAPED)).isEqualTo(3);
    }

    @Test
    public void app_list_renders_beans() {
        String html = factory.template("page-app-list.html").instance()
                .add("apps", List.of(TApp.builder()
                        .appName("render-1")
                        .taskName("render-task")
                        .taskHost("host-1")
                        .taskType(TaskType.ZIP_ARCHIVE)
                        .build()))
                .createText();

        assertThat(html)
                .contains("render-task")
                .contains("ZIP_ARCHIVE")
                .contains("href=\"/dc-operator/app/render-1\"");
    }

    @Test
    public void app_list_escapes_text_and_href() {
        String html = factory.template("page-app-list.html").instance()
                .add("apps", List.of(TApp.builder()
                        .appName(EVIL)
                        .taskName(EVIL)
                        .taskHost(EVIL)
                        .taskType(TaskType.ZIP_ARCHIVE)
                        .build()))
                .createText();

        assertThat(html)
                .doesNotContain("<script>alert").doesNotContain("\" onmouseover")
                .contains("href=\"/dc-operator/app/" + EVIL_ESCAPED + "\"");
        assertThat(countOf(html, EVIL_ESCAPED)).isEqualTo(4);
    }

    @Test
    public void app_view_renders() {
        String html = factory.template("page-app-view.html").instance()
                .add("appName"       , "render-1")
                .add("taskName"      , "render-task")
                .add("taskHost"      , "host-1")
                .add("taskType"      , TaskType.ZIP_ARCHIVE)
                .add("taskCheckText" , "no changes")
                .add("taskCheckColor", "green")
                .add("agentUrl"      , "https://host-1:8051/dc-agent")
                .createText();

        assertThat(html)
                .contains("host-1 -> https://host-1:8051/dc-agent")
                .contains("no changes");
    }

    @Test
    public void app_view_escapes_path_name_and_check_output() {
        String diff = "- <image>old</image>\n+ <image>new & better</image>";
        String html = factory.template("page-app-view.html").instance()
                .add("appName"       , EVIL)                   // taken from the request path
                .add("taskName"      , EVIL)
                .add("taskHost"      , EVIL)
                .add("taskType"      , TaskType.ZIP_ARCHIVE)
                .add("taskCheckText" , diff)                   // DOCKER_CHECK diff of files from the config repo
                .add("taskCheckColor", EVIL)
                .add("agentUrl"      , EVIL)
                .createText();

        assertThat(html)
                .doesNotContain("<script>alert").doesNotContain("<image>")
                .contains("- &lt;image&gt;old&lt;/image&gt;\n+ &lt;image&gt;new &amp; better&lt;/image&gt;");
        assertThat(countOf(html, EVIL_ESCAPED)).isEqualTo(3);
    }

    @Test
    public void react_index_escapes_asset_uris() {
        String html = factory.template("page-react-index.html").instance()
                .add("ASSETS_INDEX_JS_URI" , EVIL)
                .add("ASSETS_INDEX_CSS_URI", EVIL)
                .createText();

        assertThat(html).doesNotContain("<script>alert").doesNotContain("\" onmouseover");
        assertThat(countOf(html, EVIL_ESCAPED)).isEqualTo(2);
    }

    @Test
    public void every_template_turns_on_html_escaping() throws IOException {
        Path dir = Paths.get("src/main/resources/templates");
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> templates = files.filter(Files::isRegularFile).toList();
            assertThat(templates).isNotEmpty();
            for (Path template : templates) {
                assertThat(Files.readString(template, StandardCharsets.UTF_8))
                        .as("%s must start with %s", template.getFileName(), FTL_HEADER)
                        .startsWith(FTL_HEADER + "\n");
            }
        }
    }

    private static int countOf(String aText, String aNeedle) {
        int count = 0;
        for (int i = aText.indexOf(aNeedle); i >= 0; i = aText.indexOf(aNeedle, i + aNeedle.length())) {
            count++;
        }
        return count;
    }
}
