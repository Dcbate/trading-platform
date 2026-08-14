package com.dcbate.tradingplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.ai.AnomalyDetector;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.config.TradingProperties;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.PriceUpdateEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class PriceFeedServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Mock
    private AnomalyDetector anomalyDetector;

    private PriceFeedServiceImpl priceFeedService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        KafkaTopicsProperties topics = new KafkaTopicsProperties(
                "orders", "orders-validated", "trades", "prices", "risk-alerts",
                "payments", "payments-validated", "ledger-entries", "settlements", "fraud-alerts", "notifications", "notifications-dlq",
                "account-activity", "transfers", "loans");
        TradingProperties tradingProperties =
                new TradingProperties(List.of("EUR/USD"), new TradingProperties.PriceFeed(2000, new BigDecimal("10")));
        priceFeedService = new PriceFeedServiceImpl(
                redisTemplate, kafkaEventPublisher, topics, tradingProperties, anomalyDetector, new SimpleMeterRegistry());
    }

    @Test
    void publishesATickAndCachesTheNewPrice() {
        when(valueOperations.get("price:EUR/USD")).thenReturn(null);

        priceFeedService.publishTick("EUR/USD");

        verify(kafkaEventPublisher).publish(eq("prices"), eq("EUR/USD"), any(PriceUpdateEvent.class));
        verify(valueOperations).set(eq("price:EUR/USD"), anyString(), eq(Duration.ofMinutes(1)));
    }

    @Test
    void moveBeyondThresholdIsAnomalous() {
        boolean anomalous = priceFeedService.isAnomalousMove(new BigDecimal("100.00"), new BigDecimal("115.00"));

        assertThat(anomalous).isTrue();
    }

    @Test
    void moveWithinThresholdIsNotAnomalous() {
        boolean anomalous = priceFeedService.isAnomalousMove(new BigDecimal("100.00"), new BigDecimal("105.00"));

        assertThat(anomalous).isFalse();
    }
}
