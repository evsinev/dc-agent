package com.payneteasy.dcagent.controlplane.service.serviceview;

import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceViewResponse;

import java.io.*;
import java.nio.file.Files;

import static com.payneteasy.dcagent.controlplane.service.serviceview.ServiceFiles.createServiceFiles;
import static java.lang.String.join;
import static java.nio.charset.StandardCharsets.UTF_8;

public class ServiceViewDelegate {
    private final File servicesDir;

    public ServiceViewDelegate(File servicesDir) {
        this.servicesDir = servicesDir;
    }

    public ServiceViewResponse getServiceView(String aServiceName) {
        ServiceFiles files = createServiceFiles(servicesDir, aServiceName);

        return ServiceViewResponse.builder()
                .serviceName(aServiceName)
                .runContent(readContent(files.getRunFile()))
                .logRunContent(readContent(files.getLogRunFile()))
                .lastLogLines(readLastLines(aServiceName))
                .build();
    }

    private String readLastLines(String aServiceName) {
        File file = new File("/var/log/" + aServiceName + "/current");
        if (!file.exists()) {
            return "No log file at " + file.getAbsolutePath();
        }

        CycleBuffer<String> cycleBuffer = new CycleBuffer<>(new String[20]);

        try (LineNumberReader in = new LineNumberReader(new InputStreamReader(new FileInputStream(file), UTF_8))) {

            String line;
            while ((line = in.readLine()) != null) {
                cycleBuffer.add(line);
            }

            return join("\n", cycleBuffer.toList());

        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file " + file.getAbsolutePath(), e);
        }

    }

    private String readContent(File aFile) {
        try {
            return new String(Files.readAllBytes(aFile.toPath()), UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file " + aFile.getAbsolutePath(), e);
        }
    }

}
