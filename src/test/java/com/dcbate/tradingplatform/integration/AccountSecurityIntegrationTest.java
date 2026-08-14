package com.dcbate.tradingplatform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcbate.tradingplatform.account.api.dto.AccountRequest;
import com.dcbate.tradingplatform.account.api.dto.AccountResponse;
import com.dcbate.tradingplatform.domain.AccountType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the "strong security" requirement end-to-end through the real (non-dev) JWT filter
 * chain: a client's token can only see/act on their own accounts. Unlike the other integration
 * tests, this one deliberately does NOT activate the {@code dev} profile, so
 * {@code SecurityConfig.jwtSecurityFilterChain} and {@code CallerPrincipal}'s ownership checks
 * are both actually exercised, not bypassed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountSecurityIntegrationTest {

    // Matches application.yml's jwt.secret default — must be >= 32 bytes for HS256.
    private static final String JWT_SECRET = "local-dev-secret-change-me-please-32bytes";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("trading")
            .withUsername("trading")
            .withPassword("trading");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.0"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("chronicle.trade-journal.path", () -> "target/test-chronicle/" + UUID.randomUUID());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private String jwtFor(String clientId, String... roles) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(clientId)
                .claim("roles", List.of(roles))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJwt.sign(new MACSigner(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return signedJwt.serialize();
    }

    private HttpEntity<?> authorized(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private HttpEntity<Object> authorized(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void ownerCanReadTheirAccountButAnotherClientIsForbidden() throws Exception {
        String clientAToken = jwtFor("client-A", "CLIENT");
        String clientBToken = jwtFor("client-B", "CLIENT");

        AccountRequest openRequest = new AccountRequest("client-A", AccountType.CHECKING, "USD", new BigDecimal("500.00"));
        ResponseEntity<AccountResponse> openResponse = restTemplate.exchange(
                "/v1/accounts", HttpMethod.POST, authorized(clientAToken, openRequest), AccountResponse.class);
        assertThat(openResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID accountId = openResponse.getBody().accountId();

        ResponseEntity<AccountResponse> ownerRead = restTemplate.exchange(
                "/v1/accounts/{id}", HttpMethod.GET, authorized(clientAToken), AccountResponse.class, accountId);
        assertThat(ownerRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerRead.getBody().clientId()).isEqualTo("client-A");

        ResponseEntity<String> strangerRead = restTemplate.exchange(
                "/v1/accounts/{id}", HttpMethod.GET, authorized(clientBToken), String.class, accountId);
        assertThat(strangerRead.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void requestWithoutATokenIsUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v1/accounts/{id}", String.class, UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
