package com.dcbate.tradingplatform.loadtest;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Two scenarios — sellers and buyers — both hitting the same currency pair and price
 * concurrently, so orders actually cross in the matching engine instead of just resting. Defaults
 * are scaled down for a quick local run; override with {@code -DordersPerSec=100
 * -DdurationSeconds=600} for the full "50 traders, 100 orders/sec, 10 minutes" scenario. Run via
 * {@code mvn gatling:test -Dgatling.simulationClass=com.dcbate.tradingplatform.loadtest.FxTradingLoadSimulation}.
 *
 * <p>Goal from the original sizing exercise: order match p99 under 50ms. Order submission latency
 * is what this measures directly (submit -> 201); actual fill/match latency happens
 * asynchronously off the Kafka {@code orders-validated} topic and isn't captured by an HTTP
 * response time — see {@code docs/TRADING_SYSTEM.md} for the async matching flow.
 */
public class FxTradingLoadSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    private static final int ORDERS_PER_SEC = Integer.getInteger("ordersPerSec", 10);
    private static final int DURATION_SECONDS = Integer.getInteger("durationSeconds", 20);
    private static final String CURRENCY_PAIR = System.getProperty("currencyPair", "EUR/USD");
    private static final String PRICE = System.getProperty("price", "1.0800");

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    // 25 distinct trader identities per side (~50 traders total, matching the original sizing
    // exercise) cycling round-robin — a single reused clientId would spuriously trip
    // RiskServiceImpl's per-client order-velocity limit (5 orders/60s) at any real order rate.
    private static final int TRADERS_PER_SIDE = 25;

    private static Iterator<Map<String, Object>> traderFeeder(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return Stream.generate((java.util.function.Supplier<Map<String, Object>>) () ->
                Map.<String, Object>of("clientId", prefix + "-" + (counter.getAndIncrement() % TRADERS_PER_SIDE))).iterator();
    }

    private final ScenarioBuilder sellers = scenario("FX sellers")
            .feed(traderFeeder("loadtest-seller"))
            .exec(http("Submit SELL order")
                    .post("/v1/orders")
                    .body(StringBody("{\"clientId\":\"#{clientId}\",\"currencyPair\":\"" + CURRENCY_PAIR
                            + "\",\"side\":\"SELL\",\"quantity\":10,\"price\":" + PRICE + "}"))
                    .check(status().is(201)));

    private final ScenarioBuilder buyers = scenario("FX buyers")
            .feed(traderFeeder("loadtest-buyer"))
            .exec(http("Submit BUY order")
                    .post("/v1/orders")
                    .body(StringBody("{\"clientId\":\"#{clientId}\",\"currencyPair\":\"" + CURRENCY_PAIR
                            + "\",\"side\":\"BUY\",\"quantity\":10,\"price\":" + PRICE + "}"))
                    .check(status().is(201)));

    {
        setUp(
                sellers.injectOpen(constantUsersPerSec(ORDERS_PER_SEC / 2.0).during(Duration.ofSeconds(DURATION_SECONDS))),
                buyers.injectOpen(constantUsersPerSec(ORDERS_PER_SEC / 2.0).during(Duration.ofSeconds(DURATION_SECONDS))))
                .protocols(httpProtocol)
                .assertions(global().failedRequests().percent().lt(1.0));
    }
}
