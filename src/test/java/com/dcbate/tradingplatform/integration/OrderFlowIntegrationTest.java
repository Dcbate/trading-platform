package com.dcbate.tradingplatform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcbate.tradingplatform.chronicle.TradeJournalReader;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.trading.api.dto.OrderRequest;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import com.dcbate.tradingplatform.trading.repository.TradeRepository;
import java.math.BigDecimal;
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
 * End-to-end proof that a resting order and a crossing order really produce a trade through the
 * full pipeline: REST API -> {@code orders} -> Risk Service -> {@code orders-validated} ->
 * Matching Engine -> {@code trades} -> Execution Service -> Postgres + Chronicle journal, against
 * real Kafka, Postgres, and Redis containers (no mocks).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class OrderFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("trading")
            .withUsername("trading")
            .withPassword("trading");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.0"))
            // Same reasoning as docker-compose.yml: rely solely on KafkaConfig's NewTopic beans
            // for partition counts, not the broker's auto-create-with-1-partition default.
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
    private TradeRepository tradeRepository;

    @Autowired
    private TradeJournalReader tradeJournalReader;

    @Test
    void crossingOrdersProduceAFilledTradeEndToEnd() {
        String currencyPair = "E2ETEST";
        UUID sellOrderId = submitOrder(new OrderRequest("seller-1", currencyPair, OrderSide.SELL, new BigDecimal("5"), new BigDecimal("42.00")));
        UUID buyOrderId = submitOrder(new OrderRequest("buyer-1", currencyPair, OrderSide.BUY, new BigDecimal("5"), new BigDecimal("42.00")));

        OrderResponse filledBuy = awaitStatus(buyOrderId, OrderStatus.FILLED);
        OrderResponse filledSell = awaitStatus(sellOrderId, OrderStatus.FILLED);

        assertThat(filledBuy.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(filledSell.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(tradeRepository.findAll()).anyMatch(t -> t.getBuyOrderId().equals(buyOrderId) && t.getSellOrderId().equals(sellOrderId));
        assertThat(tradeJournalReader.readAll()).anyMatch(t -> t.buyOrderId().equals(buyOrderId));
    }

    private UUID submitOrder(OrderRequest request) {
        ResponseEntity<OrderResponse> response = restTemplate.postForEntity("/v1/orders", request, OrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().orderId();
    }

    private OrderResponse awaitStatus(UUID orderId, OrderStatus expected) {
        for (int attempt = 0; attempt < 50; attempt++) {
            ResponseEntity<OrderResponse> response = restTemplate.getForEntity("/v1/orders/{id}", OrderResponse.class, orderId);
            OrderResponse body = response.getBody();
            if (body != null && body.status() == expected) {
                return body;
            }
            sleep(200);
        }
        throw new AssertionError("Order " + orderId + " did not reach status " + expected + " in time");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
