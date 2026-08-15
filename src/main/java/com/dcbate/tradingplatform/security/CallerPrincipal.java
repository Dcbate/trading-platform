package com.dcbate.tradingplatform.security;

import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Who's calling, resolved once at the controller boundary and passed explicitly into services —
 * not read from a static {@code SecurityContextHolder} inside them, so ownership checks stay
 * trivially unit-testable (construct a fake principal, no security-context mocking).
 *
 * <p>{@code clientId} is the JWT {@code sub} claim ({@link Authentication#getName()} already
 * returns it for a {@code JwtAuthenticationToken} — no custom claim needed). {@code staff} is
 * true for bank staff roles, which may act across clients; {@code CLIENT} may only act on their
 * own resources.
 *
 * <p>Controllers resolve {@code authentication} via a plain {@code Authentication} method
 * parameter, which Spring MVC binds from {@code HttpServletRequest.getUserPrincipal()} — and by
 * design that returns {@code null} for an anonymous principal (Spring Security deliberately
 * doesn't treat "anonymous" as "authenticated" there). In the {@code dev} profile that's reachable
 * on every endpoint (the whole filter chain permits all). In the {@code !dev} profile it's
 * reachable only on the specific paths {@code SecurityConfig} explicitly permits without a JWT —
 * currently {@code /v1/game/**}, since Game Mode is playable without an account (see
 * docs/GAME_MODE.md §6/§7) — every other endpoint's {@code !dev} chain still requires real
 * authentication before a request reaches a controller. Treating null as staff mirrors dev's
 * existing convenience of granting the anonymous principal every role; for Game Mode specifically
 * it's also what lets an anonymous request supply its own (guest-generated) {@code clientId}
 * without a real identity to check it against — see {@code GameServiceImpl.startSession}.
 */
public record CallerPrincipal(String clientId, boolean staff) {

    private static final Set<String> STAFF_ROLES = Set.of(
            "ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_COMPLIANCE_OFFICER", "ROLE_TRADER");

    public static CallerPrincipal from(Authentication authentication) {
        if (authentication == null) {
            return new CallerPrincipal("dev-anonymous", true);
        }
        boolean staff = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(STAFF_ROLES::contains);
        return new CallerPrincipal(authentication.getName(), staff);
    }

    /** Throws unless the caller owns {@code resourceClientId} or is staff. */
    public void requireOwner(String resourceClientId) {
        if (!staff && !clientId.equals(resourceClientId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "clientId=%s may not access a resource owned by clientId=%s".formatted(clientId, resourceClientId));
        }
    }
}
