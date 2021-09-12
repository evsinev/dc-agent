package com.payneteasy.dcagent.modules.war;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.charset.StandardCharsets;

public class LastLogLines {

    private final File     file;
    private final String[] lines;
    private       int      position = 0;

    public LastLogLines(File file, int aCount) {
        this.file = file;
        lines = new String[aCount];
    }

    public void showLastLines(Appendable aAppendable) throws IOException {
        try(LineNumberReader in = new LineNumberReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while((line = in.readLine()) != null) {
                putLine(line);
            }
        }

        for(int i=position; i<lines.length; i++) {
            append(i, aAppendable);
        }
        for(int i=0; i<position; i++) {
            append(i, aAppendable);
        }
    }

    private void append(int i, Appendable aAppendable) throws IOException {
        String line = lines[i];
        if(line != null) {
            aAppendable.append(line).append('\n');
        }
    }

    private void putLine(String aLine) {
        if(position >= lines.length) {
            position = 0;
        }
        lines[position] = aLine;
        position++;
    }
}
