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
 * "Normal day": concurrent clients each open a pair of accounts and transfer between them
 * repeatedly. Defaults are scaled down for a quick local run against `docker compose`; override
 * with {@code -Dusers=1000 -DtransfersPerUser=5} (etc.) for the full 1,000-user scenario. Run via
 * {@code mvn gatling:test -Dgatling.simulationClass=com.dcbate.tradingplatform.loadtest.TransferLoadSimulation}.
 *
 * <p>Goal from the original sizing exercise: p99 latency under 500ms at 1,000 concurrent users.
 * This class only defines the scenario — the actual pass/fail numbers are whatever the HTML
 * report from the run says, not hardcoded here.
 */
public class TransferLoadSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    private static final int USERS = Integer.getInteger("users", 50);
    private static final int TRANSFERS_PER_USER = Integer.getInteger("transfersPerUser", 5);
    private static final int RAMP_SECONDS = Integer.getInteger("rampSeconds", 20);

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private final Iterator<Map<String, Object>> clientFeeder = Stream.generate((java.util.function.Supplier<Map<String, Object>>) () -> {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return Map.<String, Object>of("clientA", "loadtest-" + id + "-a", "clientB", "loadtest-" + id + "-b");
    }).iterator();

    private final ChainBuilder openAccounts = feed(clientFeeder)
            .exec(http("Open account A")
                    .post("/v1/accounts")
                    .body(StringBody("{\"clientId\":\"#{clientA}\",\"accountType\":\"CHECKING\",\"currency\":\"USD\",\"openingBalance\":100000.00}"))
                    .check(status().is(201))
                    .check(jsonPath("$.accountId").saveAs("accountIdA")))
            .exec(http("Open account B")
                    .post("/v1/accounts")
                    .body(StringBody("{\"clientId\":\"#{clientB}\",\"accountType\":\"CHECKING\",\"currency\":\"USD\",\"openingBalance\":0.00}"))
                    .check(status().is(201))
                    .check(jsonPath("$.accountId").saveAs("accountIdB")));

    private final ChainBuilder transferOnce = exec(http("Transfer")
            .post("/v1/transfers")
            .body(StringBody("{\"fromAccountId\":\"#{accountIdA}\",\"toAccountId\":\"#{accountIdB}\",\"amount\":1.00}"))
            .check(status().is(201)));

    private final ScenarioBuilder scn = scenario("Transfer load")
            .exec(openAccounts)
            .repeat(TRANSFERS_PER_USER).on(transferOnce);

    {
        setUp(scn.injectOpen(rampUsers(USERS).during(Duration.ofSeconds(RAMP_SECONDS))))
                .protocols(httpProtocol)
                .assertions(global().responseTime().percentile(99).lt(500), global().failedRequests().percent().lt(1.0));
    }
}
