package br.com.conversa.app.api;

import br.com.conversa.app.order.OrderBoard;
import br.com.conversa.app.order.OrderBoard.OrderView;
import br.com.conversa.app.tenant.TenantTransactions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orders board of the panel (M8): the signed-in staff member's tenant only.
 */
@RestController
@RequestMapping("/api/v1/orders")
class OrdersController {

    record StatusChange(@NotNull OrderBoard.Status status) {
    }

    private final OrderBoard board;
    private final TenantTransactions tenants;

    OrdersController(OrderBoard board, TenantTransactions tenants) {
        this.board = board;
        this.tenants = tenants;
    }

    /** @param scope {@code open} (default): in progress; {@code recent}: every order of the last 24 hours */
    @GetMapping
    List<OrderView> list(@AuthenticationPrincipal StaffUser user, @RequestParam(defaultValue = "open") String scope) {
        return tenants.inTenant(user.tenant(), () -> board.list(user.tenant(), !"recent".equals(scope)));
    }

    @GetMapping("/{id}")
    ResponseEntity<OrderView> one(@AuthenticationPrincipal StaffUser user, @PathVariable UUID id) {
        return ResponseEntity.of(tenants.inTenant(user.tenant(), () -> board.find(user.tenant(), id)));
    }

    @PostMapping("/{id}/status")
    OrderView move(@AuthenticationPrincipal StaffUser user, @PathVariable UUID id,
                   @Valid @RequestBody StatusChange change) {
        return tenants.inTenant(user.tenant(), () -> board.move(user.tenant(), id, change.status()));
    }

    @ExceptionHandler(OrderBoard.InvalidTransition.class)
    ResponseEntity<Map<String, String>> invalid(OrderBoard.InvalidTransition e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> unknown(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
