package com.payneteasy.dcagent.core.modules.zipversion;

import com.payneteasy.dcagent.core.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Step 8 of {@code zip-archive-version}: {@code POST reloadUrl} and the service's answer, the whole
 * exchange — connect, headers, body — bounded by {@code waitTimeout}, after which it is cancelled
 * and the connection closed. {@code java.net.http}, not {@code HttpURLConnection}: there
 * {@code disconnect()} from another thread blocks on the lock held by the thread reading a
 * dripping body. The body is capped at {@value #BODY_LIMIT} bytes — the subscriber cancels the
 * rest — so a long fast answer does not hold the directory lock either.
 * <p>{@link #reload} never throws: every failure is an {@link Outcome}.
 */
public final class ReloadClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ReloadClient.class);

    static final int BODY_LIMIT = 256;

    public sealed interface Outcome {
    }

    /** The service answered: its status and the first line of its body (control characters removed). */
    public record Answered(int status, String reason) implements Outcome {
        public boolean confirmed() {
            return status >= 200 && status < 300;
        }
    }

    /** No complete answer within {@code waitTimeout}: the service may still be finishing. */
    public record TimedOut() implements Outcome {
    }

    /** No answer at all — refused connection, a broken exchange; {@code cause} is the exception type only. */
    public record Failed(String cause) implements Outcome {
    }

    private final ExecutorService executor;
    private final HttpClient      client;

    public ReloadClient() {
        AtomicInteger threads = new AtomicInteger();
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "zip-archive-version-reload-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        // No connectTimeout: the client is shared and waitTimeout differs per command — the
        // deadline of each call bounds the connect too.
        client = HttpClient.newBuilder()
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public Outcome reload(ZipArchiveVersionSettings aSettings, String aVersion) {
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<String>> future = null;
        try {
            HttpRequest request = aSettings.reloadRequest(aVersion);
            future = client.sendAsync(request, info -> new CappedBody(BODY_LIMIT));
            HttpResponse<String> response = future.get(aSettings.waitTimeout().toNanos(), TimeUnit.NANOSECONDS);
            Answered answered = new Answered(response.statusCode(), firstLine(response.body()));
            LOG.info("Reload of {} for {} answered {} in {} ms", Strings.forLog(aVersion), Strings.forLog(aSettings.name()), answered.status(), millisSince(started));
            return answered;
        } catch (TimeoutException e) {
            LOG.warn("Reload of {} for {}: no answer within {}", Strings.forLog(aVersion), Strings.forLog(aSettings.name()), aSettings.waitTimeout());
            return new TimedOut();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) {
                LOG.warn("Reload of {} for {}: no answer within {}", Strings.forLog(aVersion), Strings.forLog(aSettings.name()), aSettings.waitTimeout());
                return new TimedOut();
            }
            return failed(aSettings, aVersion, e.getCause() != null ? e.getCause() : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(aSettings, aVersion, e);
        } catch (Exception e) {
            // request building included: its messages may quote a header value — the type only
            return failed(aSettings, aVersion, e);
        } finally {
            if (future != null && !future.isDone()) {
                // closes the connection: a dripping service does not keep a thread or a socket
                future.cancel(true);
            }
        }
    }

    private static Failed failed(ZipArchiveVersionSettings aSettings, String aVersion, Throwable aError) {
        String cause = aError.getClass().getSimpleName();
        LOG.warn("Reload of {} for {} failed: {}", Strings.forLog(aVersion), Strings.forLog(aSettings.name()), cause);
        return new Failed(cause);
    }

    private static long millisSince(long aStarted) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - aStarted);
    }

    /** The first line, control characters dropped; already at most {@value #BODY_LIMIT} bytes. */
    static String firstLine(String aBody) {
        if (aBody == null) {
            return "";
        }
        int end = aBody.length();
        for (int i = 0; i < aBody.length(); i++) {
            char ch = aBody.charAt(i);
            if (ch == '\n' || ch == '\r') {
                end = i;
                break;
            }
        }
        StringBuilder line = new StringBuilder(end);
        for (int i = 0; i < end; i++) {
            char ch = aBody.charAt(i);
            if (!Character.isISOControl(ch)) {
                line.append(ch);
            }
        }
        return line.toString().trim();
    }

    @Override
    public void close() {
        client.shutdownNow();
        executor.shutdownNow();
    }

    /** Keeps the first {@code limit} bytes, then cancels the subscription: the response completes with them. */
    static final class CappedBody implements HttpResponse.BodySubscriber<String> {

        private final int                       limit;
        private final ByteArrayOutputStream     bytes  = new ByteArrayOutputStream();
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private       Flow.Subscription         subscription;

        CappedBody(int aLimit) {
            limit = aLimit;
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription aSubscription) {
            subscription = aSubscription;
            aSubscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> aItems) {
            for (ByteBuffer item : aItems) {
                int take = Math.min(item.remaining(), limit - bytes.size());
                byte[] chunk = new byte[take];
                item.get(chunk);
                bytes.write(chunk, 0, take);
                if (bytes.size() >= limit) {
                    subscription.cancel();
                    complete();
                    return;
                }
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable aError) {
            result.completeExceptionally(aError);
        }

        @Override
        public void onComplete() {
            complete();
        }

        private void complete() {
            result.complete(new String(bytes.toByteArray(), UTF_8));
        }
    }
}
