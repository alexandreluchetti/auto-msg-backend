package br.com.conversa.app.outbound;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local stand-in for the Graph API: records requests and answers each message with a new wamid,
 * or with the error set by {@link #failWith}.
 */
final class FakeGraphApi implements AutoCloseable {

    private final HttpServer server;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile int errorStatus;
    private volatile String errorBody;

    FakeGraphApi() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            int status = errorStatus == 0 ? 200 : errorStatus;
            String answer;
            if (errorStatus != 0) {
                answer = errorBody;
            } else if (body.contains("\"status\":\"read\"")) {
                answer = "{\"success\":true}";
            } else {
                answer = "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.OUT"
                        + sequence.incrementAndGet() + "\"}]}";
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void failWith(int status, String body) {
        this.errorBody = body;
        this.errorStatus = status;
    }

    void succeed() {
        this.errorStatus = 0;
    }

    List<String> requestBodies() {
        return requestBodies;
    }

    void reset() {
        requestBodies.clear();
        sequence.set(0);
        succeed();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
