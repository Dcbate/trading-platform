package com.dcbate.tradingplatform.trading.repository;

import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code sumOpenNotionalByClientId} backs the Risk Service's notional-limit check. */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByClientIdOrderByCreatedAtDesc(String clientId);

    /**
     * Backs {@code MatchingEngineServiceImpl}'s startup recovery — the in-memory order book is
     * empty on every restart, so every order still resting (not yet fully filled or rejected) has
     * to be replayed back into it in original arrival order, or it becomes permanently unmatchable.
     */
    List<Order> findByStatusInOrderByCreatedAtAsc(List<OrderStatus> statuses);

    @Query(
            "SELECT COALESCE(SUM(o.quantity * o.price), 0) FROM Order o "
                    + "WHERE o.clientId = :clientId AND o.status IN :statuses")
    BigDecimal sumOpenNotionalByClientId(
            @Param("clientId") String clientId, @Param("statuses") List<OrderStatus> statuses);
}
