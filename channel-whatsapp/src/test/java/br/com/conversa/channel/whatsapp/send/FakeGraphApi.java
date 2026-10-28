package br.com.conversa.channel.whatsapp.send;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
        server.createContext("/", exchange -> {
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

    @Override
    public void close() {
        server.stop(0);
    }
}
