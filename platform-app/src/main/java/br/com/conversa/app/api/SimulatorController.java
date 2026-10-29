package br.com.conversa.app.api;

import br.com.conversa.app.simulator.SimulatorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The simulator (ADR-011 step H8): admins try the draft persona as a customer would, with the real
 * model and tools, without WhatsApp. The answer carries its human pace ({@code atMs}), which the
 * panel plays back with the "digitando..." indicator.
 */
@RestController
@RequestMapping("/api/v1/simulator")
class SimulatorController {

    /** @param replyId the id of a button or list row tapped instead of typing */
    record Say(@Size(max = 4096) String text, @Size(max = 200) String replyId) {
    }

    private final SimulatorService simulator;

    SimulatorController(SimulatorService simulator) {
        this.simulator = simulator;
    }

    @PostMapping("/messages")
    SimulatorService.Reply say(@AuthenticationPrincipal StaffUser user, @Valid @RequestBody Say say) {
        return simulator.say(user.tenant(), user.id(), say.text(), say.replyId());
    }

    @GetMapping("/messages")
    List<SimulatorService.Line> history(@AuthenticationPrincipal StaffUser user) {
        return simulator.history(user.tenant(), user.id());
    }

    /** Starts the simulated conversation over. */
    @DeleteMapping("/messages")
    ResponseEntity<Void> reset(@AuthenticationPrincipal StaffUser user) {
        simulator.reset(user.tenant(), user.id());
        return ResponseEntity.noContent().build();
    }
}
