package com.dcbate.tradingplatform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class CallerPrincipalTest {

    @Test
    void fromTreatsNullAuthenticationAsStaff() {
        // The local-dev-convenience shortcut fromGameCaller exists specifically to avoid reusing
        // for anonymous Game Mode traffic — see its javadoc.
        CallerPrincipal caller = CallerPrincipal.from(null);

        assertThat(caller.staff()).isTrue();
    }

    @Test
    void fromGameCallerNeverGrantsStaffToAnAnonymousGuest() {
        // The vulnerability this guards against, confirmed live: reusing CallerPrincipal.from's
        // null-is-staff branch for real anonymous Game Mode traffic made requireOwner a no-op for
        // the entire feature — any guest could act on any other guest's session.
        CallerPrincipal caller = CallerPrincipal.fromGameCaller(null, "guest-abc");

        assertThat(caller.staff()).isFalse();
        assertThat(caller.clientId()).isEqualTo("guest-abc");
    }

    @Test
    void fromGameCallerRejectsAGuestActingOnADifferentGuestsSession() {
        CallerPrincipal attacker = CallerPrincipal.fromGameCaller(null, "guest-attacker");

        assertThatThrownBy(() -> attacker.requireOwner("guest-victim"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void fromGameCallerAllowsAGuestActingOnItsOwnSession() {
        CallerPrincipal owner = CallerPrincipal.fromGameCaller(null, "guest-victim");

        owner.requireOwner("guest-victim"); // must not throw
    }

    @Test
    void fromGameCallerFallsBackToAnEmptyStringWhenTheHeaderIsMissingRatherThanThrowing() {
        // A missing X-Game-Client-Id header must fail closed (rejected) rather than NPE out of
        // requireOwner's clientId.equals(...) call — "" can never match a real session's clientId.
        CallerPrincipal caller = CallerPrincipal.fromGameCaller(null, null);

        assertThat(caller.staff()).isFalse();
        assertThatThrownBy(() -> caller.requireOwner("guest-victim"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void fromGameCallerPrefersARealJwtIdentityOverTheGuestHeader() {
        // A logged-in client's verified identity always wins — the header is only ever a fallback
        // for genuinely anonymous requests, never something that can override a real JWT.
        Authentication jwt = new TestingAuthenticationToken("real-client-1", null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));

        CallerPrincipal caller = CallerPrincipal.fromGameCaller(jwt, "guest-someone-else");

        assertThat(caller.clientId()).isEqualTo("real-client-1");
        assertThat(caller.staff()).isFalse();
    }
}
