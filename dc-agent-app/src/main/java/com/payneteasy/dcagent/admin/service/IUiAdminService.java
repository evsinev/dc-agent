package com.payneteasy.dcagent.admin.service;

import com.payneteasy.apiservlet.VoidRequest;
import com.payneteasy.dcagent.admin.service.messages.TaskListResponse;
import com.payneteasy.dcagent.admin.service.messages.TaskViewRequest;
import com.payneteasy.dcagent.admin.service.messages.TaskViewJarResponse;
import com.payneteasy.dcagent.admin.service.messages.save.FetchUrlConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.messages.save.JarConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.messages.save.SaveArtifactConfigSaveRequest;
import com.payneteasy.dcagent.admin.service.messages.save.ZipArchiveConfigSaveRequest;
import jakarta.ws.rs.Path;

@Path("/task")
public interface IUiAdminService {

    @Path("/list")
    TaskListResponse listTasks(VoidRequest aVoid);

    @Path("/jar/get")
    TaskViewJarResponse getJarTask(TaskViewRequest aRequest);

    @Path("/fetch-url/save")
    void saveFetchUrl(FetchUrlConfigSaveRequest aRequest);

    @Path("/jar/save")
    void saveJar(JarConfigSaveRequest aRequest);

    @Path("/save-artifact/save")
    void saveArtifact(SaveArtifactConfigSaveRequest aRequest);

    @Path("/zip-archive/save")
    void saveArchive(ZipArchiveConfigSaveRequest aRequest);
}
