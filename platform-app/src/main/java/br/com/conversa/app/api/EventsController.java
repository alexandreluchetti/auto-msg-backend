package br.com.conversa.app.api;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live events of the panel (SSE): new and changed orders, handoffs. The panel refetches what an
 * event names.
 */
@RestController
@RequestMapping("/api/v1/events")
class EventsController {

    private final PanelEventStream stream;

    EventsController(PanelEventStream stream) {
        this.stream = stream;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@AuthenticationPrincipal StaffUser user) {
        return stream.open(user.tenant());
    }
}
