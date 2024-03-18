package com.payneteasy.dcagent.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.util.concurrent.Callable;

@Command(name = "create-task", description = "Create task")
public class CreateTaskCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger( CreateTaskCommand.class );

    @ParentCommand
    private DcAgentCliApp parent;

    @Option(names = {"--task"}, description = "Task name", required = true)
    String taskName;

    @Option(names = {"--type"}, description = "Task type. Valid values: ${COMPLETION-CANDIDATES}", required = true)
    CliTaskType taskType;

    @Override
    public Integer call() throws Exception {
        LOG.debug("Base Directory {},  task dir {}"
                , parent.baseDirectory.getAbsolutePath()
                , new File(parent.baseDirectory, taskName).getAbsolutePath()
        );
        return null;
    }

    
}
