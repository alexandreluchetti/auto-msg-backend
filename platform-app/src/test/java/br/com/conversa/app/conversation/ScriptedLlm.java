package br.com.conversa.app.conversation;

import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.llm.LlmContent;
import br.com.conversa.core.llm.LlmException;
import br.com.conversa.core.llm.LlmRequest;
import br.com.conversa.core.llm.LlmResponse;
import br.com.conversa.core.llm.LlmUsage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/** Language model that plays back scripted answers and records the requests (never the real API). */
public class ScriptedLlm implements LlmClient {

    private final Deque<Supplier<LlmResponse>> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    public static LlmResponse text(String text) {
        return new LlmResponse("claude-opus-5-5", List.of(new LlmContent.Text(text)), LlmResponse.StopReason.END_TURN,
                new LlmUsage(1000, 100, 500, 0), "provider-turn");
    }

    public static LlmResponse toolCall(String id, String name, String inputJson) {
        return new LlmResponse("claude-opus-5-5", List.of(new LlmContent.ToolCall(id, name, inputJson)),
                LlmResponse.StopReason.TOOL_USE, new LlmUsage(1000, 50, 500, 0), "provider-turn-" + id);
    }

    public static LlmResponse refusal() {
        return new LlmResponse("claude-opus-5-5", List.of(), LlmResponse.StopReason.REFUSAL, new LlmUsage(100, 0, 0, 0),
                null);
    }

    public ScriptedLlm then(LlmResponse response) {
        script.add(() -> response);
        return this;
    }

    public ScriptedLlm thenFail(boolean retryable) {
        script.add(() -> {
            throw new LlmException("scripted failure", retryable, null);
        });
        return this;
    }

    public void reset() {
        script.clear();
        requests.clear();
    }

    public List<LlmRequest> requests() {
        return requests;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public synchronized LlmResponse complete(LlmRequest request) {
        requests.add(request);
        if (script.isEmpty()) {
            throw new IllegalStateException("No scripted answer left");
        }
        return script.removeFirst().get();
    }
}
