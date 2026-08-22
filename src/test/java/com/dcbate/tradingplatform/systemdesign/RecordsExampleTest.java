package com.dcbate.tradingplatform.systemdesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Records (<a href="https://openjdk.org/jeps/395">JEP 395</a>, stable since Java 16, used
 * heavily throughout this codebase's DTO layer — 74 of them at last count). See
 * docs/TECH_STACK_INTERVIEW_GUIDE.md, "Java 21."
 *
 * <p>The compiler generates the constructor, accessors, {@code equals}/{@code hashCode}, and
 * {@code toString} from the field list alone — this test proves that generated behavior rather
 * than just asserting it.
 */
class RecordsExampleTest {

    record TradeQuote(String symbol, BigDecimal price) {
    }

    @Test
    void twoRecordsWithTheSameFieldValuesAreEqual() {
        TradeQuote a = new TradeQuote("AAPL", new BigDecimal("190.00"));
        TradeQuote b = new TradeQuote("AAPL", new BigDecimal("190.00"));

        // No hand-written equals()/hashCode() anywhere — the compiler derived both from the
        // record's field list, which is exactly why 74 DTOs in this codebase don't need Lombok's
        // @EqualsAndHashCode or a manually-written equals method.
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void accessorsAreNamedAfterTheFieldNotPrefixedWithGet() {
        TradeQuote quote = new TradeQuote("AAPL", new BigDecimal("190.00"));

        // quote.symbol(), not quote.getSymbol() — a small but real difference from a Lombok
        // @Getter class, worth knowing so it doesn't look like a compile error the first time.
        assertThat(quote.symbol()).isEqualTo("AAPL");
        assertThat(quote.price()).isEqualByComparingTo("190.00");
    }

    @Test
    void toStringIsGeneratedAndReadableWithNoExtraCode() {
        TradeQuote quote = new TradeQuote("AAPL", new BigDecimal("190.00"));

        assertThat(quote.toString()).contains("AAPL", "190.00");
    }

    record ValidatedOrder(String symbol, int quantity) {
        // A compact canonical constructor — runs before the fields are even assigned, the
        // standard place to put record-level validation without writing a full constructor body.
        ValidatedOrder {
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
        }
    }

    @Test
    void aCompactConstructorCanValidateBeforeTheRecordIsEvenConstructed() {
        assertThat(new ValidatedOrder("AAPL", 10).quantity()).isEqualTo(10);

        assertThatThrownBy(() -> new ValidatedOrder("AAPL", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quantity must be positive");
    }
}
