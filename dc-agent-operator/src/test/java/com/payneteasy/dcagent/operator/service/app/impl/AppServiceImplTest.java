package com.payneteasy.dcagent.operator.service.app.impl;

import com.payneteasy.dcagent.operator.service.app.model.TApp;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AppServiceImplTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private AppServiceImpl service;

    @Before
    public void setUp() throws Exception {
        write("beta.yaml", "appName: beta\ntaskName: t-beta\n");
        write("alpha.yaml", "appName: alpha\ntaskName: t-alpha\n");
        service = new AppServiceImpl(folder.getRoot());
    }

    private void write(String name, String content) throws Exception {
        Files.write(new File(folder.getRoot(), name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void get_app_parses_the_matching_yaml() {
        assertThat(service.getApp("alpha").getAppName()).isEqualTo("alpha");
    }

    @Test
    public void get_app_of_missing_app_throws_illegal_state() {
        assertThatThrownBy(() -> service.getApp("absent"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void list_apps_is_sorted_by_app_name() {
        assertThat(service.listApps())
                .extracting(TApp::getAppName)
                .containsExactly("alpha", "beta");
    }

    @Test
    public void list_apps_response_wraps_all_apps() {
        List<TApp> apps = service.listApps(null).getApps();

        assertThat(apps).hasSize(2);
    }
}
