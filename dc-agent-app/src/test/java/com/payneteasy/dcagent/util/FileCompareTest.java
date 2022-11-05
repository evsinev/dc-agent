package com.payneteasy.dcagent.util;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static com.payneteasy.dcagent.util.FileCompare.isFileIdentical;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileCompareTest {

    @Test
    public void is_file_identical_bytes() throws IOException {
        byte[] bytes    = "hello".getBytes(UTF_8);
        File   tempFile = File.createTempFile("file-ident-", "-txt");
        SafeFiles.writeFile(tempFile, bytes);
        assertTrue("File should be identical. See " + tempFile.getAbsolutePath(), isFileIdentical(tempFile, bytes));
        assertFalse(isFileIdentical(tempFile, "no hello".getBytes(UTF_8)));
        assertTrue(tempFile.delete());
    }
}