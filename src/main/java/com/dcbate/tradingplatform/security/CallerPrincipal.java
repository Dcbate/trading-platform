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
 * doesn't treat "anonymous" as "authenticated" there). That's only reachable in the {@code dev}
 * profile: the {@code !dev} JWT filter chain requires real authentication before a request ever
 * reaches a controller, so {@code authentication} can't be null there. Treating null as staff
 * mirrors dev's existing convenience of granting the anonymous principal every role.
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
