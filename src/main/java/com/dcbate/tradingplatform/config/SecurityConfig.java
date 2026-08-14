package com.dcbate.tradingplatform.config;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two mutually-exclusive filter chains, selected by Spring profile:
 *
 * <ul>
 *   <li>{@code dev} profile: permits all requests. Local-only convenience so the API can be
 *       exercised with plain curl without minting a JWT first. Never active in production.
 *   <li>default: stateless JWT resource server. Roles CLIENT (a retail customer) / TRADER / ADMIN /
 *       AUDITOR / COMPLIANCE_OFFICER are read from the {@code roles} claim and enforced via
 *       {@code @PreAuthorize}. Client-facing endpoints additionally check resource ownership via
 *       {@link com.dcbate.tradingplatform.security.CallerPrincipal} — {@code @PreAuthorize} alone
 *       only proves the caller is *some* client, not that they own the specific account/payment
 *       being accessed.
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Bean
    @Profile("dev")
    public SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        // permitAll at the HTTP layer isn't enough on its own: @PreAuthorize is a separate,
        // always-on AOP layer that still needs an authenticated principal with roles. Granting
        // every trading role to the anonymous dev principal satisfies both without disabling
        // method security itself, so dev behaves like prod minus the JWT step.
        http.csrf(csrf -> csrf.disable())
                .anonymous(anon -> anon.authorities("ROLE_TRADER", "ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_COMPLIANCE_OFFICER", "ROLE_CLIENT"))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Profile("!dev")
    public SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/prometheus", "/v1/swagger-ui/**", "/v1/api-docs/**",
                                "/playground.html", "/v1/loans/products", "/v1/accounts/currencies")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        SecretKeySpec key = new SecretKeySpec(jwtSecret.getBytes(), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }

    private org.springframework.core.convert.converter.Converter<Jwt, AbstractAuthenticationToken>
            jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = authoritiesConverter.convert(jwt);
            return authorities == null ? List.of() : authorities.stream().collect(Collectors.toList());
        });
        return converter;
    }
}
