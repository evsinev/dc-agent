package com.payneteasy.dcagent.cli;

import com.payneteasy.dcagent.cli.config.CliConfiguration;
import com.payneteasy.dcagent.cli.config.ICliConfigReaderService;
import com.payneteasy.dcagent.cli.config.impl.CliConfigReaderServiceImpl;
import com.payneteasy.dcagent.cli.config.impl.LogProcessListener;
import com.payneteasy.dcagent.core.config.model.TaskType;
import com.payneteasy.dcagent.core.job.create.ICreateJobService;
import com.payneteasy.dcagent.core.job.create.impl.CreateJobServiceImpl;
import com.payneteasy.dcagent.core.job.create.messages.CreateJobParam;
import com.payneteasy.dcagent.core.jobs.send.ISendJobService;
import com.payneteasy.dcagent.core.jobs.send.SendJobParam;
import com.payneteasy.dcagent.core.jobs.send.SendJobResult;
import com.payneteasy.dcagent.core.jobs.send.impl.SendJobServiceImpl;
import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.util.ZipDirCreate;
import com.payneteasy.osprocess.api.ProcessDescriptor;
import com.payneteasy.osprocess.api.ProcessException;
import com.payneteasy.osprocess.impl.ProcessServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.util.concurrent.Callable;

import static com.payneteasy.dcagent.core.job.create.JobIds.createJobId;
import static com.payneteasy.dcagent.core.util.Gsons.PRETTY_GSON;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

@Command(name = "create-job", description = "Create task")
public class CreateJobCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger( CreateJobCommand.class );

    @ParentCommand
    private DcAgentCliApp parent;

    @Option(names = {"--task"}, description = "Task name", required = true)
    String taskName;

    @Option(names = {"--type"}, description = "Task type. Valid values: ${COMPLETION-CANDIDATES}", required = true)
    TaskType taskType;

    @Option(names = {"--host"}, description = "Task host", required = true)
    String taskHost;

    @Override
    public Integer call() throws Exception {
        ICliConfigReaderService configReader   = new CliConfigReaderServiceImpl(parent.baseDirectory);
        CliConfiguration        config         = configReader.readConfig();
        ICreateJobService       createJob      = new CreateJobServiceImpl();
        ISendJobService         sendJob        = new SendJobServiceImpl(PRETTY_GSON);
        String                  jobId          = createJobId();

        LOG.info("Creating task {} for {} with job {}", taskName, taskHost, jobId);
        
        try(TempFile taskFile = new ZipDirCreate()
                .baseDir       ( config.getTaskDir(taskName)  )
                .firstSegment  ( taskName                     )
                .createZipFile ( new TempFile("task-" + taskName, "zip"))
        ) {
            try(TempFile jobFile = createJob.createJob(CreateJobParam.builder()
                    .jobId           ( jobId              )
                    .taskFile        ( taskFile.getFile() )
                    .taskType        ( taskType           )
                    .taskName        ( taskName           )
                    .taskHost        ( taskHost           )
                    .consumerKey     ( config.getConsumerKey()           )
                    .privateKey      ( config.getClientPrivateKey()      )
                    .certificate     ( config.getClientCertificate()     )
                    .certificateFile ( config.getClientCertificateFile() )
                    .build())
            ) {
                SendJobResult result = sendJob.sendJob(SendJobParam.builder()
                        .baseUrl           ( config.getBaseUrl()           )
                        .clientCertificate ( config.getClientCertificate() )
                        .jobFile           ( jobFile.getFile()             )
                        .clientPrivateKey  ( config.getClientPrivateKey()  )
                        .caCertificate     ( config.getCaCertificate()     )
                        .jobId             ( jobId                         )
                        .build());

                openUrl(result.getJobUrl(), config);
            }

        }
;
        return 0;
    }

    private void openUrl(String jobUrl, CliConfiguration config) {
        LOG.info("Opening url {}", jobUrl);
        
        ProcessServiceImpl processService = new ProcessServiceImpl();

        try {
            processService.startProcess(
                    new ProcessDescriptor(
                              config.getOpenUrlCommand()
                            , config.getOpenUrlCommandArgs()
                                    .stream()
                                    .map(it -> it.replace("$url", jobUrl))
                                    .collect(toList())
                            , emptyList()
                            , new File(".")
                    )
                    , new LogProcessListener()
            );
        } catch (ProcessException e) {
            throw new IllegalStateException("Cannot run command " + config.getOpenUrlCommand() + " " + config.getOpenUrlCommandArgs());
        }
    }


}
