package br.com.conversa.app.compose;

import br.com.conversa.app.compose.ResponseComposer.Bubble;
import br.com.conversa.app.compose.ResponseComposer.ChoiceBubble;
import br.com.conversa.app.conversation.AssistantProperties;
import br.com.conversa.app.outbound.OutboundMessageRepository;
import br.com.conversa.app.outbound.OutboundRequest;
import br.com.conversa.core.channel.OutboundMessage;
import br.com.conversa.core.tenant.TenantId;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Puts one answer in the outbox as a turn (ADR-011): typing indicator and bubbles at the times
 * {@link HumanPace} plans, grouped by a turn id derived from {@code ref} so a retried answer maps to
 * the same rows. The first bubble keeps the key {@code ref}; without the typing indicator the
 * answered message gets a plain read receipt. Must run inside the tenant's transaction.
 */
@Component
public class TurnSender {

    private final OutboundMessageRepository outbound;
    private final AssistantProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    TurnSender(OutboundMessageRepository outbound, AssistantProperties properties, ObjectMapper mapper, Clock clock) {
        this.outbound = outbound;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The turn id of the answer {@code ref}. */
    public static UUID turnOf(String ref) {
        return UUID.nameUUIDFromBytes(ref.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param answered the customer's message being answered (read receipt / typing indicator)
     */
    public void send(TenantId tenant, String phoneNumberId, String waId, String answered, String ref,
                     List<Bubble> bubbles, JsonNode config) {
        UUID turn = turnOf(ref);
        Instant now = clock.instant();
        List<HumanPace.Scheduled> plan = HumanPace.plan(bubbles.stream().map(Bubble::text).toList(), config, now,
                properties.paceScale());
        int seq = 0;
        int typings = 0;
        if (plan.stream().noneMatch(step -> step.step() == HumanPace.Step.TYPING)) {
            outbound.enqueue("read:" + answered, tenant,
                    OutboundRequest.readReceipt(phoneNumberId, waId, answered, mapper), now, turn, seq++);
        }
        for (HumanPace.Scheduled step : plan) {
            if (step.step() == HumanPace.Step.TYPING) {
                outbound.enqueue(ref + ":typing:" + ++typings, tenant,
                        OutboundRequest.typing(phoneNumberId, waId, answered, mapper), step.at(), turn, seq++);
            } else {
                String key = step.bubble() == 0 ? ref : ref + ":" + (step.bubble() + 1);
                outbound.enqueue(key, tenant, request(phoneNumberId, waId, bubbles.get(step.bubble())), step.at(),
                        turn, seq++);
            }
        }
    }

    private OutboundRequest request(String phoneNumberId, String waId, Bubble bubble) {
        if (bubble instanceof ChoiceBubble choice) {
            List<OutboundMessage.Button> options = choice.choices().stream()
                    .map(option -> new OutboundMessage.Button(option.id(), option.title())).toList();
            return choice.asButtons()
                    ? OutboundRequest.buttons(phoneNumberId, waId, choice.text(), options, mapper)
                    : OutboundRequest.options(phoneNumberId, waId, choice.text(), "Ver opções", options, mapper);
        }
        return OutboundRequest.text(phoneNumberId, waId, bubble.text(), mapper);
    }
}
