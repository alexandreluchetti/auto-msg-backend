package br.com.conversa.app.webhook;

import br.com.conversa.channel.whatsapp.WhatsAppProperties;
import br.com.conversa.channel.whatsapp.webhook.InvalidWebhookPayloadException;
import br.com.conversa.channel.whatsapp.webhook.WebhookChallenge;
import br.com.conversa.channel.whatsapp.webhook.WebhookEvent;
import br.com.conversa.channel.whatsapp.webhook.WebhookPayloadParser;
import br.com.conversa.channel.whatsapp.webhook.WebhookSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Webhook da WhatsApp Cloud API. Só valida, grava e responde: o processamento é assíncrono
 * (ADR-004). Responder rápido evita reentregas da Meta.
 */
@RestController
@RequestMapping("/webhooks/whatsapp")
class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final WhatsAppProperties properties;
    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookPayloadParser parser;
    private final InboundEventRepository repository;

    WhatsAppWebhookController(WhatsAppProperties properties, WebhookSignatureVerifier signatureVerifier,
                              WebhookPayloadParser parser, InboundEventRepository repository) {
        this.properties = properties;
        this.signatureVerifier = signatureVerifier;
        this.parser = parser;
        this.repository = repository;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                  @RequestParam(name = "hub.verify_token", required = false) String token,
                                  @RequestParam(name = "hub.challenge", required = false) String challenge) {
        return WebhookChallenge.verify(mode, token, challenge, properties.verifyToken())
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    log.warn("Verificação do webhook recusada");
                    return ResponseEntity.status(403).build();
                });
    }

    @PostMapping(consumes = MediaType.ALL_VALUE)
    ResponseEntity<Void> receive(@RequestBody byte[] body,
                                 @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        if (!signatureVerifier.isValid(body, signature)) {
            log.warn("Webhook com assinatura inválida descartado");
            return ResponseEntity.status(401).build();
        }
        List<WebhookEvent> events;
        try {
            events = parser.parse(body);
        } catch (InvalidWebhookPayloadException e) {
            log.warn("Webhook assinado com payload inválido: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        int inserted = repository.saveAll(events);
        log.info("Webhook recebido: {} evento(s), {} novo(s), {} duplicado(s)",
                events.size(), inserted, events.size() - inserted);
        return ResponseEntity.ok().build();
    }
}
