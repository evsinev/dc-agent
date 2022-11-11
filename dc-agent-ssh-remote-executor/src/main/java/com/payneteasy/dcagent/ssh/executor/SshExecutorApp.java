package com.payneteasy.dcagent.ssh.executor;

import com.payneteasy.dcagent.core.modules.docker.ActionLoggerImpl;
import com.payneteasy.dcagent.core.modules.docker.PushDockerAction;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesDefinitionDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.ServicesLogDir;
import com.payneteasy.dcagent.core.modules.docker.dirs.TempDir;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemCheckImpl;
import com.payneteasy.dcagent.core.modules.docker.filesystem.FileSystemWriterImpl;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "ssh-executor", mixinStandardHelpOptions = true)
public class SshExecutorApp implements Callable<Integer> {

    private enum ActionType {
        push, check
    }

    @CommandLine.Option(names = {"-td", "--temp-dir"}, description = "Temp directory")
    private File tempDirectory = new File("/tmp/dc-agent");

    @CommandLine.Option(names = {"-sd", "--service-definition-dir"}, description = "Service definition directory")
    private File serviceDefinitionDirectory = new File("/etc/service.d");

    @CommandLine.Option(names = {"-ld", "--service-log-dir"}, description = "Services log directory")
    private File servicesLogDirectory = new File("/var/log");

    @CommandLine.Option(names = {"-zf", "--zip-file"}, description = "Zip file with service parameters", required = true)
    private File zipFile;

    @CommandLine.Parameters(index = "0", description = "Action")
    private ActionType actionType;


    @Override
    public Integer call() throws Exception {
        TempDir               tempDir               = new TempDir(tempDirectory).createDir();
        ServicesDefinitionDir servicesDefinitionDir = new ServicesDefinitionDir(serviceDefinitionDirectory);
        ServicesLogDir        servicesLogDir        = new ServicesLogDir(servicesLogDirectory);
        String                name                  = zipFile.getName().replace(".zip", "");

        switch (actionType) {
            case push:
                PushDockerAction pushDockerAction = new PushDockerAction(name, tempDir, servicesDefinitionDir, servicesLogDir, new ActionLoggerImpl(), FileSystemWriterImpl::new);
                pushDockerAction.pushService(zipFile);
                break;

            case check:
                PushDockerAction checkDockerAction = new PushDockerAction(name, tempDir, servicesDefinitionDir, servicesLogDir, new ActionLoggerImpl(), FileSystemCheckImpl::new);
                checkDockerAction.pushService(zipFile);
                break;

            default:
                throw new IllegalStateException("No any action: check or push");
        }


        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SshExecutorApp()).execute(args);
        System.exit(exitCode);
    }

}
