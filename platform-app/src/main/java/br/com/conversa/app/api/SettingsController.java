package br.com.conversa.app.api;

import br.com.conversa.app.tenant.InvalidTenantConfigException;
import br.com.conversa.app.tenant.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * The business's settings in the panel (ADR-011 step H8, D-013): persona, human pace and the rest
 * of the tenant configuration, edited as a draft (tried in the simulator) and then published to
 * WhatsApp. Everyone reads; only admins change. The prompt template is not editable here.
 */
@RestController
@RequestMapping("/api/v1/settings")
class SettingsController {

    /**
     * @param published the business's own published settings (what WhatsApp uses, before the pack defaults)
     * @param draft     the business's own draft, or null
     * @param effective pack defaults merged with the draft (or the published settings without one)
     * @param defaults  the pack defaults the business's settings are merged over
     * @param schema    the pack's JSON schema of the settings, for the panel's forms
     */
    record Settings(JsonNode published, JsonNode draft, JsonNode effective, JsonNode defaults, JsonNode schema,
                    List<TenantService.ConfigVersion> versions) {
    }

    record Saved(int version) {
    }

    private final TenantService tenants;
    private final ObjectMapper mapper;

    SettingsController(TenantService tenants, ObjectMapper mapper) {
        this.tenants = tenants;
        this.mapper = mapper;
    }

    @GetMapping
    Settings settings(@AuthenticationPrincipal StaffUser user) {
        var pack = tenants.packOf(user.tenant());
        return new Settings(mapper.readTree(tenants.publishedConfig(user.tenant())),
                tenants.draftConfig(user.tenant()).map(mapper::readTree).orElse(null),
                tenants.effectiveDraftConfig(user.tenant()), mapper.readTree(pack.defaultConfigJson()),
                mapper.readTree(pack.configSchemaJson()), tenants.versions(user.tenant()));
    }

    /** Replaces the draft with the business's own settings (not merged with the defaults). */
    @PutMapping("/draft")
    Saved saveDraft(@AuthenticationPrincipal StaffUser user, @RequestBody JsonNode config) {
        if (!config.isObject()) {
            throw new InvalidTenantConfigException(List.of("As configurações devem ser um objeto JSON."));
        }
        return new Saved(tenants.saveDraft(user.tenant(), config.toString(), user.id()));
    }

    /** Makes the draft what WhatsApp uses. */
    @PostMapping("/publish")
    ResponseEntity<?> publish(@AuthenticationPrincipal StaffUser user) {
        return tenants.publishDraft(user.tenant(), user.id())
                .<ResponseEntity<?>>map(version -> ResponseEntity.ok(new Saved(version)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Não há rascunho para publicar.")));
    }

    @ExceptionHandler(InvalidTenantConfigException.class)
    ResponseEntity<Map<String, Object>> invalid(InvalidTenantConfigException e) {
        return ResponseEntity.status(422).body(Map.of("error", "Configurações inválidas.",
                "details", e.errors()));
    }
}
