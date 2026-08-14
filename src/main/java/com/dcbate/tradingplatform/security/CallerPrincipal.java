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
 */
public record CallerPrincipal(String clientId, boolean staff) {

    private static final Set<String> STAFF_ROLES = Set.of(
            "ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_COMPLIANCE_OFFICER", "ROLE_TRADER");

    public static CallerPrincipal from(Authentication authentication) {
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
