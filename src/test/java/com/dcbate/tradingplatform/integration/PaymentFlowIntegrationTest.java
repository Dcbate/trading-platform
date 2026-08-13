package com.dcbate.tradingplatform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcbate.tradingplatform.domain.LedgerEntry;
import com.dcbate.tradingplatform.domain.LedgerEntryType;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.payment.api.dto.PaymentRequest;
import com.dcbate.tradingplatform.payment.api.dto.PaymentResponse;
import com.dcbate.tradingplatform.payment.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end proof of the payment pipeline: REST API -> {@code payments} -> Fraud Detection ->
 * {@code payments-validated} -> Settlement (saga) -> ledger + notification, against real Kafka,
 * Postgres, and Redis containers. Covers both saga outcomes: a normal amount settles, and an
 * amount above the simulated bank-clearing threshold compensates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class PaymentFlowIntegrationTest {

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

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void normalPaymentSettlesAndLedgerNetsToZero() {
        PaymentRequest request = new PaymentRequest("payer-1", new BigDecimal("250.00"), "idem-" + UUID.randomUUID(), "US");

        UUID paymentId = submitPayment(request);
        PaymentResponse settled = awaitStatus(paymentId, PaymentStatus.SETTLED);

        assertThat(settled.status()).isEqualTo(PaymentStatus.SETTLED);

        List<LedgerEntry> entries = ledgerEntryRepository.findByPaymentId(paymentId);
        assertThat(entries).hasSize(2);
        BigDecimal debits = sum(entries, LedgerEntryType.DEBIT);
        BigDecimal credits = sum(entries, LedgerEntryType.CREDIT);
        assertThat(debits).isEqualByComparingTo(credits);
    }

    @Test
    void amountAboveBankThresholdFailsAndCompensates() {
        PaymentRequest request = new PaymentRequest("payer-2", new BigDecimal("600000.00"), "idem-" + UUID.randomUUID(), "US");

        UUID paymentId = submitPayment(request);
        PaymentResponse failed = awaitStatus(paymentId, PaymentStatus.FAILED);

        assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);

        List<LedgerEntry> entries = ledgerEntryRepository.findByPaymentId(paymentId);
        // Original double-entry (2) plus the reversal (2) = 4, and they must still net to zero.
        assertThat(entries).hasSize(4);
        assertThat(sum(entries, LedgerEntryType.DEBIT)).isEqualByComparingTo(sum(entries, LedgerEntryType.CREDIT));
    }

    private BigDecimal sum(List<LedgerEntry> entries, LedgerEntryType type) {
        return entries.stream()
                .filter(e -> e.getEntryType() == type)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private UUID submitPayment(PaymentRequest request) {
        ResponseEntity<PaymentResponse> response = restTemplate.postForEntity("/v1/payments", request, PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody().paymentId();
    }

    private PaymentResponse awaitStatus(UUID paymentId, PaymentStatus expected) {
        for (int attempt = 0; attempt < 50; attempt++) {
            ResponseEntity<PaymentResponse> response = restTemplate.getForEntity("/v1/payments/{id}", PaymentResponse.class, paymentId);
            PaymentResponse body = response.getBody();
            if (body != null && body.status() == expected) {
                return body;
            }
            sleep(200);
        }
        throw new AssertionError("Payment " + paymentId + " did not reach status " + expected + " in time");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
