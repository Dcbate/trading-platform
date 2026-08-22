package com.dcbate.tradingplatform.systemdesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Layered architecture (Controller → Service → Repository) — the shape every real feature in
 * this app follows. See docs/TECH_STACK_INTERVIEW_GUIDE.md, "Layered architecture."
 *
 * <p>The teaching point isn't that the layers exist — it's what they buy you: because the
 * "repository" here is just an interface, the service can be tested completely in isolation, with
 * a fake repository standing in for a real database. That's true of every {@code *ServiceImplTest}
 * in this codebase ({@code GameServiceImplTest}, {@code SettlementServiceImplTest}, …) — this is
 * the pattern that makes them possible without a running Postgres.
 */
class LayeredArchitectureExampleTest {

    // --- the toy layers ---

    interface GreetingRepository {
        Optional<String> findGreetingFor(String name);
    }

    static class GreetingService {
        private final GreetingRepository repository;

        GreetingService(GreetingRepository repository) {
            this.repository = repository;
        }

        /** Business logic lives here — never in the repository, never in the controller. */
        String greet(String name) {
            return repository.findGreetingFor(name).orElse("Hello, " + name + "!");
        }
    }

    static class GreetingController {
        private final GreetingService service;

        GreetingController(GreetingService service) {
            this.service = service;
        }

        /** A controller's only job: translate a request into a service call. */
        String handleGreetRequest(String name) {
            return service.greet(name);
        }
    }

    // --- the tests ---

    @Test
    void serviceFallsBackToADefaultGreetingWhenTheRepositoryHasNone() {
        GreetingRepository fakeRepository = mock(GreetingRepository.class);
        when(fakeRepository.findGreetingFor("Ada")).thenReturn(Optional.empty());
        GreetingService service = new GreetingService(fakeRepository);

        assertThat(service.greet("Ada")).isEqualTo("Hello, Ada!");
    }

    @Test
    void serviceUsesAStoredGreetingWhenTheRepositoryHasOne() {
        GreetingRepository fakeRepository = mock(GreetingRepository.class);
        when(fakeRepository.findGreetingFor("Ada")).thenReturn(Optional.of("Welcome back, Ada"));
        GreetingService service = new GreetingService(fakeRepository);

        assertThat(service.greet("Ada")).isEqualTo("Welcome back, Ada");
    }

    @Test
    void controllerReachesTheRepositoryOnlyThroughTheService() {
        GreetingRepository fakeRepository = mock(GreetingRepository.class);
        when(fakeRepository.findGreetingFor("Ada")).thenReturn(Optional.empty());
        GreetingController controller = new GreetingController(new GreetingService(fakeRepository));

        String response = controller.handleGreetRequest("Ada");

        assertThat(response).isEqualTo("Hello, Ada!");
        // Proves the call actually flowed Controller -> Service -> Repository, not a shortcut
        // straight from the controller.
        verify(fakeRepository).findGreetingFor("Ada");
    }
}
