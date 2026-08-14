package com.dcbate.tradingplatform.loadtest;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Concurrent clients each open an account, then originate a loan against it. Defaults are scaled
 * down for a quick local run; override with {@code -Dusers=100 -DratePerSec=10} for the full
 * scenario. Run via
 * {@code mvn gatling:test -Dgatling.simulationClass=com.dcbate.tradingplatform.loadtest.LoanOriginationLoadSimulation}.
 *
 * <p>Goal from the original sizing exercise: sustain 10 loan originations/sec.
 */
public class LoanOriginationLoadSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    private static final int RATE_PER_SEC = Integer.getInteger("ratePerSec", 5);
    private static final int DURATION_SECONDS = Integer.getInteger("durationSeconds", 20);

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private final Iterator<Map<String, Object>> clientFeeder = Stream.generate((java.util.function.Supplier<Map<String, Object>>) () -> {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return Map.<String, Object>of("clientId", "loadtest-loan-" + id);
    }).iterator();

    private final ChainBuilder openAccountThenOriginate = feed(clientFeeder)
            .exec(http("Open account")
                    .post("/v1/accounts")
                    .body(StringBody("{\"clientId\":\"#{clientId}\",\"accountType\":\"CHECKING\",\"currency\":\"USD\",\"openingBalance\":0.00}"))
                    .check(status().is(201))
                    .check(jsonPath("$.accountId").saveAs("accountId")))
            .exec(http("Originate loan")
                    .post("/v1/loans")
                    .body(StringBody("{\"clientId\":\"#{clientId}\",\"accountId\":\"#{accountId}\",\"principal\":1000.00,\"productType\":\"PERSONAL_SHORT\"}"))
                    .check(status().is(201)));

    private final ScenarioBuilder scn = scenario("Loan origination load").exec(openAccountThenOriginate);

    {
        setUp(scn.injectOpen(constantUsersPerSec(RATE_PER_SEC).during(Duration.ofSeconds(DURATION_SECONDS))))
                .protocols(httpProtocol)
                .assertions(global().failedRequests().percent().lt(1.0));
    }
}
