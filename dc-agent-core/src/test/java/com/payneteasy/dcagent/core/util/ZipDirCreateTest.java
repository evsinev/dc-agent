package com.payneteasy.dcagent.core.util;

import com.payneteasy.dcagent.core.modules.zipachive.TempFile;
import com.payneteasy.dcagent.core.util.zip.ZipDirCreate;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static com.google.common.truth.Truth.assertThat;


public class ZipDirCreateTest {

    @Test
    public void test() throws IOException {
        try(TempFile file = new ZipDirCreate()
                .firstSegment("dc-agent-docker")
                .baseDir(new File("src/test/resources/dc-agent-docker"))
                .createZipFile(new TempFile("create-zip", "zip")))
        {
            assertThat(file).isNotNull();
        }

    }

}