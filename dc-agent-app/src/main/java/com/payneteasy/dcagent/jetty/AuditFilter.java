package com.payneteasy.dcagent.jetty;

import com.payneteasy.dcagent.core.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * One audit line per authorized command, after it finished: who (the key that authorized it — for a
 * GitLab token its project, ref, pipeline and job, set by {@link CheckApiKey}), the final status, and
 * the size and SHA-256 of the request body as the command read it — "complete", "partial" (refused
 * or cut short) or "not read". A request id ties this line to the authorization line and to the error
 * id {@link ErrorFilter} logs and answers. Requests that were not authorized (refused, or not
 * commands) leave no audit line; the refusal is logged where it happens. Registered before
 * {@link ErrorFilter}, so the status is the one the caller got.
 */
public class AuditFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(AuditFilter.class);

    /** The request id (set here, read by {@link CheckApiKey} for its authorization line). */
    public static final String REQUEST_ID = "dc-agent.requestId";

    /** Who authorized the command (set by {@link CheckApiKey} on success). */
    public static final String AUTHORIZED_BY = "dc-agent.authorizedBy";

    /** The id AuditFilter gave this request — the one its audit line carries — or a new one outside it. */
    public static String requestId(ServletRequest aRequest) {
        return aRequest.getAttribute(REQUEST_ID) instanceof String id ? id : UUID.randomUUID().toString();
    }

    @Override
    public void doFilter(ServletRequest aRequest, ServletResponse aResponse, FilterChain aChain) throws IOException, ServletException {
        if (!(aRequest instanceof HttpServletRequest request) || !(aResponse instanceof HttpServletResponse response)) {
            aChain.doFilter(aRequest, aResponse);
            return;
        }
        String id = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, id);
        DigestingRequest digesting = new DigestingRequest(request);
        String failed = null;
        try {
            aChain.doFilter(digesting, response);
        } catch (Throwable e) {
            // ErrorFilter answers an Exception; anything still thrown (an Error) the container answers with 500,
            // not the status the response holds now
            failed = "failed (" + e.getClass().getSimpleName() + ")";
            throw e;
        } finally {
            Object by = request.getAttribute(AUTHORIZED_BY);
            if (by != null) {
                LOG.info("Command {} {} by {}: status {}, body {} bytes ({}) sha256 {}, request {}",
                        request.getMethod(), Strings.forLog(request.getRequestURI()), by,
                        failed != null ? failed : String.valueOf(response.getStatus()),
                        digesting.bytes, digesting.completeness(), digesting.hex(), id);
            }
        }
    }

    /** The body as the command reads it, hashed on the way. */
    private static final class DigestingRequest extends HttpServletRequestWrapper {

        private final MessageDigest      digest;
        private       ServletInputStream stream;
        private       long               bytes;
        private       boolean            ended;

        DigestingRequest(HttpServletRequest aRequest) {
            super(aRequest);
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        String hex() {
            return stream == null ? "-" : HexFormat.of().formatHex(((MessageDigest) cloneDigest()).digest());
        }

        /** "not read", "complete" (all of Content-Length, or the stream ended) or "partial" (refused or cut short). */
        String completeness() {
            if (stream == null) {
                return "not read";
            }
            long expected = getContentLengthLong();
            boolean complete = expected >= 0 ? bytes == expected : ended;
            return complete ? "complete" : "partial";
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            String charset = getCharacterEncoding() == null ? "UTF-8" : getCharacterEncoding();
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), charset));
        }

        private Object cloneDigest() {
            try {
                return digest.clone();
            } catch (CloneNotSupportedException e) {
                return digest;
            }
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream original = super.getInputStream();
                stream = new ServletInputStream() {
                    @Override
                    public int read() throws IOException {
                        int value = original.read();
                        if (value >= 0) {
                            digest.update((byte) value);
                            bytes++;
                        } else {
                            ended = true;
                        }
                        return value;
                    }

                    @Override
                    public int read(byte[] aBuffer, int aOffset, int aLength) throws IOException {
                        int count = original.read(aBuffer, aOffset, aLength);
                        if (count > 0) {
                            digest.update(aBuffer, aOffset, count);
                            bytes += count;
                        } else if (count < 0) {
                            ended = true;
                        }
                        return count;
                    }

                    @Override
                    public boolean isFinished() {
                        return original.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return original.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener aListener) {
                        original.setReadListener(aListener);
                    }
                };
            }
            return stream;
        }
    }
}
