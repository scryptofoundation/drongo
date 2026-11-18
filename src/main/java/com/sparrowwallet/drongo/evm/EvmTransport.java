package com.sparrowwallet.drongo.evm;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * How the EVM clients here ({@link LitVmRpcClient}, {@link BlockscoutClient}, {@link SourcifyClient}) reach a server.
 * None of them opens a connection of its own: each is given a transport when it is made, so the wallet that uses this
 * library decides the route for every request (a proxy, Tor, or none), and a client cannot be made that goes around it.
 *
 * A transport returns whatever status and body the server gave, a redirect included: it follows none, keeps no
 * cookies, and sends none. It throws only when no answer was had: an IOException whose type tells why where it can
 * (java.net.ConnectException when the server, or a proxy that must be used, could not be reached;
 * java.net.http.HttpTimeoutException when it took too long). When it is certain that nothing of the request left this
 * process, it throws a {@link NotSentException}.
 */
public interface EvmTransport {
    /** The longest a connection may take to be made. */
    Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /** A server's answer: its HTTP status and its body as UTF-8 text. */
    record Response(int status, String body) {}

    /**
     * No byte of the request left this process: the route was refused before any connection was tried, or the one
     * connection it needed was never made. Thrown only where that is certain; a failure that could have come after the
     * request was handed over is never this. Its cause tells why, as any other failure would.
     */
    final class NotSentException extends IOException {
        public NotSentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * One request. body is null for a GET and the bytes to POST otherwise. timeout is how long the whole exchange may
     * take, the answer's body included; a body of more than maxBytes is an IOException, whatever its status.
     */
    Response send(URI uri, Map<String, String> headers, byte[] body, Duration timeout, int maxBytes) throws IOException;

    /**
     * Straight to the server, with no proxy. For tests, and for the transport of a wallet that has checked, at the time
     * of the request, that no proxy or Tor is configured. A wallet must never hand this to a client directly.
     */
    static EvmTransport direct() {
        return Direct.INSTANCE;
    }

    /** The JDK client, connecting directly. */
    final class Direct implements EvmTransport {
        private static final Direct INSTANCE = new Direct();
        //No redirect is followed (a request's body is never replayed to another host), and there is no cookie handler.
        //HTTP/1.1 only: over HTTP/2 the JDK sends a POST again by itself after a refused stream or a GOAWAY, and a
        //second try that then failed to connect would read as "nothing was sent" although the first try went out
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).build();
        //Ends a body that outlives its request's time: the request's own timeout only covers the wait for the headers
        private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(work -> {
            Thread thread = new Thread(work, "EVM transport watchdog");
            thread.setDaemon(true);
            return thread;
        });

        private Direct() {
        }

        @Override
        public Response send(URI uri, Map<String, String> headers, byte[] body, Duration timeout, int maxBytes) throws IOException {
            long end = System.nanoTime() + timeout.toNanos();
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
            headers.forEach(builder::header);
            HttpRequest request = (body == null ? builder.GET() : builder.POST(HttpRequest.BodyPublishers.ofByteArray(body))).build();
            HttpResponse<InputStream> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for " + uri.getHost(), e);
            } catch(java.net.ConnectException | HttpConnectTimeoutException e) {
                //The connection was never made: nothing was sent on it
                throw new NotSentException("No connection to " + uri.getHost(), e);
            }
            InputStream in = response.body();
            AtomicBoolean late = new AtomicBoolean();
            ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
                late.set(true);
                try {
                    in.close();
                } catch(IOException e) {
                    //closing is only to end the read
                }
            }, Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            try(in) {
                String text = read(in, maxBytes);
                if(late.get()) {
                    throw new HttpTimeoutException("The answer from " + uri.getHost() + " took too long");
                }
                return new Response(response.statusCode(), text);
            } catch(IOException e) {
                if(late.get() && !(e instanceof HttpTimeoutException)) {
                    HttpTimeoutException timedOut = new HttpTimeoutException("The answer from " + uri.getHost() + " took too long");
                    timedOut.initCause(e);
                    throw timedOut;
                }
                throw e;
            } finally {
                watchdog.cancel(false);
            }
        }
    }

    /** A body of at most maxBytes as text; more is an IOException. */
    static String read(InputStream in, int maxBytes) throws IOException {
        byte[] data = in.readNBytes(maxBytes < Integer.MAX_VALUE ? maxBytes + 1 : maxBytes);
        if(data.length > maxBytes) {
            throw new IOException("The response is larger than " + maxBytes + " bytes");
        }
        return new String(data, StandardCharsets.UTF_8);
    }
}
