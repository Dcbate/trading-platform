package com.dcbate.tradingplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One Game Mode run: a client picks a {@link GameDifficulty}, starts with its seed cash, and has
 * until {@code endsAt} to grow net worth (cash + positions - loans owed) to the difficulty's goal.
 * {@code cash} is the only balance mutated directly by trades/loans; positions and loans live in
 * their own tables and are combined with the live simulated market price at valuation time.
 */
@Entity
@Table(name = "game_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameSession {

    @Id
    private UUID sessionId;

    @Column(nullable = false)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GameDifficulty difficulty;

    @Column(nullable = false)
    private BigDecimal cash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GameStatus status;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant endsAt;

    private Instant finishedAt;

    private BigDecimal finalNetWorth;
}
