package com.payneteasy.dcagent.cli;

import picocli.CommandLine;
import picocli.CommandLine.Option;

import java.io.File;

@CommandLine.Command(name = "dc-cli", mixinStandardHelpOptions = true)
public class DcAgentCliApp {

    @Option(names = {"--base-dir"}, description = "Base directory", defaultValue = ".")
    File baseDirectory;

    public static void main(String[] args) {
        System.exit(
                new CommandLine(new DcAgentCliApp())
                        .addSubcommand(CreateJobCommand.class)
                        .execute(args)
        );
    }

}
