package br.com.conversa.app.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Sign-in of the panel. The SPA calls {@code GET /csrf} first to get the CSRF cookie, then
 * {@code POST /login}; the session cookie keeps it signed in.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    record Me(String email, String name, String tenantId, String role) {

        static Me of(StaffUser user) {
            return new Me(user.getUsername(), user.displayName(), user.tenant().toString(), user.role());
        }
    }

    private final AuthenticationManager authentication;
    private final SecurityContextRepository contexts;

    AuthController(AuthenticationManager authentication, SecurityContextRepository contexts) {
        this.authentication = authentication;
        this.contexts = contexts;
    }

    /** Issues the CSRF cookie ({@code XSRF-TOKEN}). */
    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName());
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest login, HttpServletRequest request,
                            HttpServletResponse response) {
        Authentication result;
        try {
            result = authentication.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(login.email().strip(), login.password()));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "E-mail ou senha inválidos."));
        }
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(result);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return ResponseEntity.ok(Me.of((StaffUser) result.getPrincipal()));
    }

    @GetMapping("/me")
    Me me(@AuthenticationPrincipal StaffUser user) {
        return Me.of(user);
    }
}
