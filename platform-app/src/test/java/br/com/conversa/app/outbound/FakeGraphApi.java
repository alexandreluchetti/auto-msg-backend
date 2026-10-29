package br.com.conversa.app.outbound;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local stand-in for the Graph API: records requests and answers each message with a new wamid,
 * or with the error set by {@link #failWith}.
 */
public final class FakeGraphApi implements AutoCloseable {

    private final HttpServer server;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile int errorStatus;
    private volatile String errorBody;

    public FakeGraphApi() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/files/", exchange -> {
            Media media = media(exchange.getRequestURI().getPath().substring("/files/".length()));
            byte[] bytes = media == null ? new byte[0] : media.bytes();
            exchange.sendResponseHeaders(media == null ? 404 : 200, bytes.length == 0 ? -1 : bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            String[] path = exchange.getRequestURI().getPath().split("/");
            if ("GET".equals(exchange.getRequestMethod()) && path.length == 3) {
                Media media = media(path[2]);
                byte[] info = (media == null ? "{\"error\":{\"code\":100}}"
                        : "{\"url\":\"" + baseUrl() + "/files/" + path[2] + "\",\"mime_type\":\"" + media.mimeType()
                        + "\",\"file_size\":" + media.bytes().length + ",\"id\":\"" + path[2] + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(media == null ? 400 : 200, info.length);
                exchange.getResponseBody().write(info);
                exchange.close();
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
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

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void failWith(int status, String body) {
        this.errorBody = body;
        this.errorStatus = status;
    }

    void succeed() {
        this.errorStatus = 0;
    }

    public List<String> requestBodies() {
        return requestBodies;
    }

    List<String> authorizations() {
        return authorizations;
    }

    public void reset() {
        requestBodies.clear();
        authorizations.clear();
        sequence.set(0);
        media.clear();
        succeed();
    }

    record Media(String mimeType, byte[] bytes) {
    }

    private final Map<String, Media> media = new ConcurrentHashMap<>();

    /** Makes a customer media downloadable by its Cloud API id. */
    public void media(String id, String mimeType, byte[] bytes) {
        media.put(id, new Media(mimeType, bytes));
    }

    private Media media(String id) {
        return media.get(id);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
