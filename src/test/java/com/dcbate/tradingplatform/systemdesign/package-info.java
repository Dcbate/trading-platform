/**
 * Small, self-contained example tests that demonstrate common system-design patterns in
 * isolation — <strong>not</strong> tests of the banking domain. Each class here pairs with a
 * section of {@code docs/TECH_STACK_INTERVIEW_GUIDE.md} ("Part 1 — System Design Concepts", plus
 * two from "Part 3 — Java 21") and exists purely so the pattern can be read, run, and stepped
 * through in a debugger without any Spring context, database, Docker, or network — just plain
 * JUnit 5 + AssertJ over a minimal, made-up scenario (greetings, orders, exchange rates — never
 * an {@code Account} or a {@code Payment}).
 *
 * <p>Where the real banking code already implements one of these patterns for real — the
 * settlement saga in {@code payment.service.SettlementServiceImpl}, the circuit breakers in
 * {@code config.ResilienceConfig} — the example here is a separate, simplified illustration of
 * the same mechanism, not a copy of it. Read both if you want "here's the general idea" next to
 * "here's exactly how it's used for real."
 */
package com.dcbate.tradingplatform.systemdesign;
