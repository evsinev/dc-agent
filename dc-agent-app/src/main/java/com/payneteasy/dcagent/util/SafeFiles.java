package com.payneteasy.dcagent.util;

import java.io.File;
import java.io.FileFilter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SafeFiles {

    public static List<File> listFiles(File aDir, FileFilter aFilter) {
        File[] files = aDir.listFiles(aFilter);
        if (files == null) {
            return Collections.emptyList();
        }

        return Arrays.asList(files);
    }
}
