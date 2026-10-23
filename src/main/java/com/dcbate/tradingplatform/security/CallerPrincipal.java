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

    /**
     * Game Mode's own resolution — {@code /v1/game/**} is the one production path where an
     * anonymous caller is expected (playable without an account, see {@code SecurityConfig}'s
     * permitAll entry and docs/GAME_MODE.md §6/§7), and {@link #from}'s null branch was never meant
     * for that: it exists so the local {@code dev} profile's anonymous principal — which is
     * genuinely trusted, since every request in that profile is a developer on their own machine —
     * can act as staff for curl convenience. Reusing it for real anonymous guest traffic made
     * {@link #requireOwner} a silent no-op for the entire feature: any guest could take any action
     * on any other guest's session, since {@code staff=true} short-circuits the check regardless of
     * whose resource is being touched (confirmed live — a second, unrelated guest identity placed a
     * trade against a session it never created).
     *
     * <p>A real, JWT-verified identity (a logged-in client playing Game Mode) still always wins and
     * behaves exactly like {@link #from} — {@code guestClientId} only matters when
     * {@code authentication} is null. In that case the caller is a genuine anonymous guest with no
     * verifiable identity at all, so {@code guestClientId} (the same client-generated id the
     * frontend already sends via the {@code X-Game-Client-Id} header, matching whatever it used to
     * start the session) becomes their identity instead — unverified, but no longer omnipotent:
     * {@code staff} is explicitly {@code false}, so {@link #requireOwner} actually compares it
     * against the resource being accessed. A missing header falls back to {@code ""}, which can't
     * match any real session's {@code clientId} — failing closed (rejected) rather than throwing a
     * {@code NullPointerException} out of {@link #requireOwner}.
     */
    public static CallerPrincipal fromGameCaller(Authentication authentication, String guestClientId) {
        if (authentication != null) {
            return from(authentication);
        }
        return new CallerPrincipal(guestClientId != null ? guestClientId : "", false);
    }

    /** Throws unless the caller owns {@code resourceClientId} or is staff. */
    public void requireOwner(String resourceClientId) {
        if (!staff && !clientId.equals(resourceClientId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "clientId=%s may not access a resource owned by clientId=%s".formatted(clientId, resourceClientId));
        }
    }
}
