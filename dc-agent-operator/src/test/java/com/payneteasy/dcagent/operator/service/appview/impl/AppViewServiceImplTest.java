package com.payneteasy.dcagent.operator.service.appview.impl;

import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.task.send.ISendTaskService;
import com.payneteasy.dcagent.core.task.send.SendTaskResult;
import com.payneteasy.dcagent.core.util.zip.UnreadableTaskFilesException;
import com.payneteasy.dcagent.operator.service.app.IAppService;
import com.payneteasy.dcagent.operator.service.app.model.TApp;
import com.payneteasy.dcagent.operator.service.appview.messages.AppViewRequest;
import com.payneteasy.dcagent.operator.service.appview.model.AppStatusType;
import com.payneteasy.dcagent.operator.service.appview.model.AppViewResult;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.taskcreate.ITaskCreateService;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class AppViewServiceImplTest {

    private final java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(AppViewServiceImplTest.class.getClassLoader(), new Class[]{type}, handler);
    }

    private AppViewServiceImpl service(int statusCode, String text) {
        return service(statusCode, text, false);
    }

    private AppViewServiceImpl service(int statusCode, String text, boolean unreadable) {
        ISendTaskService send = proxy(ISendTaskService.class, (p, m, a) -> {
            sends.incrementAndGet();
            return SendTaskResult.builder().statusCode(statusCode).text(text).build();
        });
        IAppService app = proxy(IAppService.class, (p, m, a) -> {
            if ("getApp".equals(m.getName())) {
                return TApp.builder().appName((String) a[0]).taskName("task-1").taskHost("agent-1").build();
            }
            throw new UnsupportedOperationException(m.getName());
        });
        ITaskCreateService taskCreate = proxy(ITaskCreateService.class, (p, m, a) -> {
            if ("createTaskZipFile".equals(m.getName())) {
                if (unreadable) {
                    throw new UnreadableTaskFilesException("/tasks/task-1", java.util.List.of("conf/pki/site-a.key (cannot read)"));
                }
                return new TempFile("appview", "zip");
            }
            throw new UnsupportedOperationException(m.getName());
        });
        IOperatorConfigService config = proxy(IOperatorConfigService.class, (p, m, a) -> {
            if ("findAgentHost".equals(m.getName())) {
                return Optional.of(TAgentHost.builder().url("http://agent").token("tok").build());
            }
            throw new UnsupportedOperationException(m.getName());
        });
        return new AppViewServiceImpl(send, config, app, taskCreate);
    }

    private static AppViewRequest request() {
        return AppViewRequest.builder().appName("my-app").build();
    }

    @Test
    public void view_app_is_black_when_check_succeeds() {
        AppViewResult result = service(200, "").viewApp(request());

        assertThat(result.getAppName()).isEqualTo("my-app");
        assertThat(result.getTaskCheckColor()).isEqualTo("black");
    }

    @Test
    public void view_app_is_red_when_check_fails() {
        assertThat(service(500, "boom").viewApp(request()).getTaskCheckColor()).isEqualTo("red-600");
    }

    @Test
    public void push_app_returns_the_app_name() {
        assertThat(service(200, "").pushApp(com.payneteasy.dcagent.operator.service.appview.messages.AppPushRequest.builder()
                .appName("my-app").build()).getAppName()).isEqualTo("my-app");
    }

    @Test
    public void app_status_is_ok_when_check_clean() {
        assertThat(service(200, "").getAppStatus(request()).getStatus()).isEqualTo(AppStatusType.OK);
    }

    @Test
    public void app_status_is_drift_when_check_reports_changes() {
        assertThat(service(200, "some diff").getAppStatus(request()).getStatus()).isEqualTo(AppStatusType.DRIFT);
    }

    @Test
    public void app_status_is_error_when_check_fails() {
        assertThat(service(500, "boom").getAppStatus(request()).getStatus()).isEqualTo(AppStatusType.ERROR);
    }

    @Test
    public void an_unreadable_task_file_is_a_red_check_and_nothing_is_sent() {
        AppViewResult result = service(200, "", true).viewApp(request());

        assertThat(result.getTaskCheckColor()).isEqualTo("red-600");
        assertThat(result.getTaskCheckText()).contains("Cannot pack /tasks/task-1").contains("conf/pki/site-a.key (cannot read)");
        assertThat(sends).hasValue(0);
    }

    @Test
    public void an_unreadable_task_file_is_not_pushed() {
        AppViewResult result = service(200, "", true).pushApp(com.payneteasy.dcagent.operator.service.appview.messages.AppPushRequest.builder()
                .appName("my-app").build());

        assertThat(result.getTaskCheckColor()).isEqualTo("red-600");
        assertThat(sends).hasValue(0);
    }

    @Test
    public void a_readable_task_is_sent_once() {
        service(200, "", false).viewApp(request());

        assertThat(sends).hasValue(1);
    }

    @Test
    public void an_unreadable_task_file_makes_the_app_status_an_error() {
        assertThat(service(200, "", true).getAppStatus(request()).getStatus()).isEqualTo(AppStatusType.ERROR);
    }
}
