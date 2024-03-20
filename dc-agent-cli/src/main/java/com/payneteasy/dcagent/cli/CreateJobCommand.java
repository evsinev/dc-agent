package com.payneteasy.dcagent.cli;

import com.payneteasy.dcagent.cli.config.CliConfiguration;
import com.payneteasy.dcagent.cli.config.ICliConfigReaderService;
import com.payneteasy.dcagent.cli.config.impl.CliConfigReaderServiceImpl;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.job.create.ICreateJobService;
import com.payneteasy.dcagent.core.job.create.impl.CreateJobServiceImpl;
import com.payneteasy.dcagent.core.job.create.messages.CreateJobParam;
import com.payneteasy.dcagent.core.jobs.send.ISendJobService;
import com.payneteasy.dcagent.core.jobs.send.SendJobParam;
import com.payneteasy.dcagent.core.jobs.send.impl.SendJobServiceImpl;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.util.ZipDirCreate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

import static java.util.UUID.randomUUID;

@Command(name = "create-job", description = "Create task")
public class CreateJobCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger( CreateJobCommand.class );

    @ParentCommand
    private DcAgentCliApp parent;

    @Option(names = {"--task"}, description = "Task name", required = true)
    String taskName;

    @Option(names = {"--type"}, description = "Task type. Valid values: ${COMPLETION-CANDIDATES}", required = true)
    TaskType taskType;

    @Option(names = {"--host"}, description = "Hosts. Could be multiple", required = true)
    List<String> hosts;

    @Override
    public Integer call() throws Exception {
        ICliConfigReaderService configReader = new CliConfigReaderServiceImpl(parent.baseDirectory);
        CliConfiguration        config       = configReader.readConfig();
        ICreateJobService       createJob    = new CreateJobServiceImpl();
        ISendJobService         sendJob      = new SendJobServiceImpl();

        try(TempFile taskFile = new ZipDirCreate()
                .baseDir       ( config.getTaskDir(taskName)  )
                .firstSegment  ( taskName                     )
                .createZipFile ( new TempFile("task-" + taskName, "zip"))
        ) {
            try(TempFile jobFile = createJob.createJob(CreateJobParam.builder()
                    .jobId           ( randomUUID().toString())
                    .taskFile        ( taskFile.getFile())
                    .taskType        ( taskType )
                    .taskName        ( taskName )
                    .hosts           ( hosts)
                    .consumerKey     ( config.getConsumerKey()     )
                    .privateKey      ( config.getClientPrivateKey()      )
                    .certificate     ( config.getClientCertificate()     )
                    .certificateFile ( config.getClientCertificateFile() )
                    .build())
            ) {
                sendJob.sendJob(SendJobParam.builder()
                        .baseUrl           ( config.getBaseUrl()           )
                        .clientCertificate ( config.getClientCertificate() )
                        .jobFile           ( jobFile.getFile()             )
                        .clientPrivateKey  ( config.getClientPrivateKey()  )
                        .caCertificate     ( config.getCaCertificate()     )
                        .build());
            }

        }

        LOG.debug("Base Directory {}\ntask dir {}\nHosts: {}"
                , parent.baseDirectory.getAbsolutePath()
                , new File(parent.baseDirectory, taskName).getAbsolutePath()
                , hosts
        );
        return null;
    }


}
