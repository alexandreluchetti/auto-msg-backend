package br.com.conversa.app.privacy;

import br.com.conversa.app.outbound.OutboundMessageRepository;
import br.com.conversa.app.outbound.OutboundRequest;
import br.com.conversa.core.channel.OutboundMessage;
import br.com.conversa.core.tenant.TenantId;
import br.com.conversa.core.text.SearchText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The customer's rights under the LGPD (ARCHITECTURE section 13): the privacy notice on the first
 * answer to each customer, and erasure on request ("apagar meus dados"), confirmed with a button.
 * Erasure deletes the conversation history and removes personal data from orders, which are kept
 * for tax purposes. Must run inside the tenant's transaction.
 */
@Service
public class PrivacyService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);

    public static final String ERASE_PREFIX = "erase:";
    static final String CONFIRM_TEXT = "Você quer apagar seus dados? Isso apaga o histórico desta conversa e tira "
            + "seus dados pessoais dos pedidos (os pedidos continuam guardados sem eles, por obrigação fiscal). "
            + "Não dá para desfazer.";
    static final String ERASED_TEXT = "Pronto! Seus dados foram apagados. Se mandar uma nova mensagem, começamos "
            + "do zero.";
    static final String KEPT_TEXT = "Tudo certo, seus dados continuam guardados. Posso ajudar em algo mais?";

    private static final Pattern ASKS_FOR_ERASURE = Pattern.compile(
            "^\\s*(?:quero |gostaria de |pode |por favor )?(?:apagar|apague|excluir|exclua|deletar|delete|remover|remova)"
                    + " (?:os |todos os )?(?:meus dados|dados meus)(?: pessoais)?(?: por favor)?\\s*$");

    private final JdbcClient jdbc;
    private final OutboundMessageRepository outbound;
    private final ObjectMapper mapper;

    PrivacyService(JdbcClient jdbc, OutboundMessageRepository outbound, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.outbound = outbound;
        this.mapper = mapper;
    }

    /** Whether the customer's message asks to erase their data. */
    public static boolean asksForErasure(String text) {
        return ASKS_FOR_ERASURE.matcher(SearchText.normalize(text)).matches();
    }

    /**
     * The bot's answer, preceded by the privacy notice if this customer has not had it yet (one
     * message instead of two: each one is paid).
     */
    public String withNotice(UUID conversation, String text, JsonNode config) {
        int marked = jdbc.sql("""
                        UPDATE contact SET consent_notice_at = now()
                        WHERE consent_notice_at IS NULL
                          AND id = (SELECT contact_id FROM conversation WHERE id = :conversation)
                        """)
                .param("conversation", conversation)
                .update();
        return marked == 0 ? text : notice(config) + "\n\n" + text;
    }

    static String notice(JsonNode config) {
        String business = config.path("business").path("name").asString("o restaurante");
        String policy = config.path("privacy").path("policyUrl").asString("");
        return "_Este é o atendimento automático do " + business + ". Usamos seus dados só para atender você e "
                + "cuidar dos seus pedidos" + (policy.isBlank() ? "" : " (política de privacidade: " + policy + ")")
                + ". Para apagar seus dados a qualquer momento, envie \"apagar meus dados\"._";
    }

    /** Asks the customer to confirm the erasure with buttons. */
    public void askToConfirm(TenantId tenant, UUID conversation, String phoneNumberId, String waId, String lastWamid) {
        UUID request = jdbc.sql("""
                        INSERT INTO data_subject_request (tenant_id, contact_id, conversation_id)
                        SELECT :tenant, contact_id, id FROM conversation WHERE id = :conversation
                        RETURNING id
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation)
                .query(UUID.class).single();
        outbound.enqueue("read:" + lastWamid, tenant, OutboundRequest.readReceipt(phoneNumberId, waId, lastWamid, mapper));
        List<OutboundMessage.Button> buttons = List.of(
                new OutboundMessage.Button(ERASE_PREFIX + "yes:" + request, "Sim, apagar"),
                new OutboundMessage.Button(ERASE_PREFIX + "no:" + request, "Não"));
        outbound.enqueue("erase-ask:" + request, tenant,
                OutboundRequest.buttons(phoneNumberId, waId, CONFIRM_TEXT, buttons, mapper));
    }

    /**
     * The customer answered the confirmation ({@code erase:yes:<id>} or {@code erase:no:<id>}).
     *
     * @return true when the data was erased
     */
    public boolean onAnswer(TenantId tenant, UUID conversation, String phoneNumberId, String waId, String replyId) {
        String[] parts = replyId.split(":");
        Optional<UUID> request = parts.length == 3 ? parseUuid(parts[2]) : Optional.empty();
        boolean pending = request.isPresent() && jdbc.sql("""
                        SELECT id FROM data_subject_request
                        WHERE id = :id AND conversation_id = :conversation AND status = 'PENDING_CONFIRMATION'
                        FOR UPDATE
                        """)
                .param("id", request.get()).param("conversation", conversation)
                .query(UUID.class).optional().isPresent();
        if (!pending) {
            return false;
        }
        if (!"yes".equals(parts[1])) {
            jdbc.sql("UPDATE data_subject_request SET status = 'CANCELLED', done_at = now() WHERE id = :id")
                    .param("id", request.get()).update();
            outbound.enqueue("erase-kept:" + request.get(), tenant,
                    OutboundRequest.text(phoneNumberId, waId, KEPT_TEXT, mapper));
            return false;
        }
        outbound.enqueue("erase-done:" + request.get(), tenant,
                OutboundRequest.text(phoneNumberId, waId, ERASED_TEXT, mapper));
        erase(conversation);
        jdbc.sql("""
                        UPDATE data_subject_request
                        SET status = 'DONE', done_at = now(), phone_number_id = :phone, wa_id = :waId
                        WHERE id = :id
                        """)
                .param("phone", phoneNumberId).param("waId", waId).param("id", request.get()).update();
        log.info("Erased the data of a customer of tenant {} on request", tenant);
        return true;
    }

    /**
     * Deletes the conversation history and takes the customer's personal data out of the contact,
     * orders and checkouts. Queue rows follow in {@link #purgeQueues()}, once the confirmation is sent.
     */
    public void erase(UUID conversation) {
        UUID contact = jdbc.sql("SELECT contact_id FROM conversation WHERE id = :id")
                .param("id", conversation).query(UUID.class).single();
        jdbc.sql("DELETE FROM message WHERE conversation_id = :id").param("id", conversation).update();
        jdbc.sql("""
                        UPDATE conversation SET context = '{}', state = 'BOT', paused_until = NULL, reply_due_at = NULL,
                                                updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", conversation).update();
        jdbc.sql("UPDATE checkout SET draft = '{}' WHERE conversation_id = :id").param("id", conversation).update();
        jdbc.sql("""
                        UPDATE customer_order SET address = NULL, change_for = NULL, updated_at = now()
                        WHERE contact_id = :contact
                        """)
                .param("contact", contact).update();
        jdbc.sql("UPDATE order_item SET notes = NULL WHERE order_id IN (SELECT id FROM customer_order WHERE contact_id = :c)")
                .param("c", contact).update();
        jdbc.sql("""
                        UPDATE contact SET wa_id = 'erased:' || id, display_name = NULL, erased_at = now()
                        WHERE id = :contact
                        """)
                .param("contact", contact).update();
    }


    /**
     * Second phase of erasures (owner role, every tenant): once the confirmation of a request left
     * the outbox, the customer's queue rows lose their content and address, and so does the request.
     *
     * @return requests purged
     */
    @Transactional
    public int purgeQueues() {
        record Done(UUID id, UUID tenantId, String phoneNumberId, String waId) {
        }
        List<Done> done = jdbc.sql("""
                        SELECT r.id, r.tenant_id, r.phone_number_id, r.wa_id FROM data_subject_request r
                        WHERE r.status = 'DONE' AND r.queue_purged_at IS NULL
                          AND NOT EXISTS (SELECT 1 FROM outbound_message o
                                          WHERE o.idempotency_key = 'erase-done:' || r.id
                                            AND o.status IN ('PENDING', 'PROCESSING'))
                        """)
                .query(Done.class).list();
        for (Done request : done) {
            jdbc.sql("""
                            UPDATE inbound_event SET payload = '{}', wa_id = NULL
                            WHERE tenant_id = :tenant AND phone_number_id = :phone AND wa_id = :waId
                            """)
                    .param("tenant", request.tenantId()).param("phone", request.phoneNumberId())
                    .param("waId", request.waId()).update();
            jdbc.sql("""
                            UPDATE outbound_message SET payload = '{}', wa_id = 'erased'
                            WHERE tenant_id = :tenant AND phone_number_id = :phone AND wa_id = :waId
                              AND status IN ('SENT', 'FAILED')
                            """)
                    .param("tenant", request.tenantId()).param("phone", request.phoneNumberId())
                    .param("waId", request.waId()).update();
            jdbc.sql("""
                            UPDATE data_subject_request SET wa_id = NULL, phone_number_id = NULL, queue_purged_at = now()
                            WHERE id = :id
                            """)
                    .param("id", request.id()).update();
        }
        return done.size();
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
