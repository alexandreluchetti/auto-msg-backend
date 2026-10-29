package br.com.conversa.channel.whatsapp.send;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Local stand-in for the Graph API: records requests and answers with a canned response. */
final class FakeGraphApi implements AutoCloseable {

    record Request(String path, String authorization, String body) {
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.OUT0001\"}]}";

    FakeGraphApi() {
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
                requests.add(new Request(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"), ""));
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
            requests.add(new Request(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            byte[] answer = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void respond(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }

    List<Request> requests() {
        return requests;
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
