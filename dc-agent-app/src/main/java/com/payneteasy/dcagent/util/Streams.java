package com.payneteasy.dcagent.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Streams {

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int    count;
        while ((count = in.read(buf)) >= 0) {
            out.write(buf, 0, count);
        }
        out.flush();
    }

}
