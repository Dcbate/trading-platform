package com.dcbate.tradingplatform.domain;

/** A Game Mode session's lifecycle — evaluated lazily on every read/action, not by a scheduled job (see {@code GameServiceImpl.evaluate}). */
public enum GameStatus {
    IN_PROGRESS,
    WON,
    LOST_TIME,
    LOST_BANKRUPT
}
