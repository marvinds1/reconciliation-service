package io.github.marvinds1.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marvinds1.reconciliation.domain.Entry.Source;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MatcherTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private final Matcher matcher = new Matcher(1);
    private int line = 1;

    private Entry ledger(String ref, long amount, LocalDate date) {
        return new Entry(Source.LEDGER, ++line, ref, date, amount, "IDR", "");
    }

    private Entry statement(String ref, long amount, LocalDate date) {
        return new Entry(Source.STATEMENT, ++line, ref, date, amount, "IDR", "");
    }

    @Test
    void pairsByReferenceAndFlagsAmountDifferences() {
        var results = matcher.match(
                List.of(ledger("A", -100, DAY), ledger("B", -200, DAY)),
                List.of(statement("B", -210, DAY), statement("A", -100, DAY)));

        assertThat(results).extracting(MatchResult::status, MatchResult::rule).containsExactly(
                org.assertj.core.groups.Tuple.tuple(MatchStatus.MATCHED, MatchRule.REFERENCE),
                org.assertj.core.groups.Tuple.tuple(MatchStatus.AMOUNT_MISMATCH, MatchRule.REFERENCE));
        assertThat(results.get(1).differenceMinor()).isEqualTo(-10L);
    }

    @Test
    void currencyDifferenceIsAMismatchWithoutDifferenceAmount() {
        var l = ledger("A", -100, DAY);
        var s = new Entry(Source.STATEMENT, 99, "A", DAY, -100, "USD", "");

        MatchResult result = matcher.match(List.of(l), List.of(s)).get(0);

        assertThat(result.status()).isEqualTo(MatchStatus.AMOUNT_MISMATCH);
        assertThat(result.differenceMinor()).isNull();
    }

    @Test
    void pairsByAmountWithinDateToleranceWhenStatementHasNoReference() {
        var results = matcher.match(
                List.of(ledger("DEP-1", 5000, DAY)),
                List.of(statement("", 5000, DAY.plusDays(1))));

        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(MatchStatus.MATCHED);
            assertThat(r.rule()).isEqualTo(MatchRule.AMOUNT_AND_DATE);
        });
    }

    @Test
    void doesNotPairOutsideToleranceOrWithConflictingReferences() {
        var results = matcher.match(
                List.of(ledger("X", 5000, DAY), ledger("Y", 7000, DAY)),
                List.of(statement("", 5000, DAY.plusDays(2)), statement("Z", 7000, DAY)));

        assertThat(results).extracting(MatchResult::status).containsExactly(
                MatchStatus.MISSING_IN_STATEMENT, MatchStatus.MISSING_IN_STATEMENT,
                MatchStatus.MISSING_IN_LEDGER, MatchStatus.MISSING_IN_LEDGER);
    }

    @Test
    void amountPairingIsOneToOneAndPrefersClosestDate() {
        var far = statement("", 300, DAY.minusDays(1));
        var close = statement("", 300, DAY);

        var results = matcher.match(List.of(ledger("", 300, DAY)), List.of(far, close));

        assertThat(results.get(0).statement()).isSameAs(close);
        assertThat(results.get(1).status()).isEqualTo(MatchStatus.MISSING_IN_LEDGER);
        assertThat(results.get(1).statement()).isSameAs(far);
    }

    @Test
    void repeatedReferenceInOneFileIsADuplicate() {
        var results = matcher.match(
                List.of(ledger("A", -100, DAY)),
                List.of(statement("A", -100, DAY), statement("A", -100, DAY)));

        assertThat(results).extracting(MatchResult::status)
                .containsExactlyInAnyOrder(MatchStatus.DUPLICATE, MatchStatus.MATCHED);
    }

    @Test
    void everyEntryAppearsInExactlyOneResult() {
        List<Entry> ledger = new ArrayList<>();
        List<Entry> statement = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ledger.add(ledger(i % 3 == 0 ? "" : "R" + (i % 150), i % 17, DAY.plusDays(i % 4)));
            statement.add(statement(i % 5 == 0 ? "" : "R" + (i % 170), i % 13, DAY.plusDays(i % 3)));
        }

        var results = matcher.match(ledger, statement);

        assertThat(results.stream().flatMap(r -> Stream.of(r.ledger(), r.statement()))
                .filter(java.util.Objects::nonNull))
                .hasSize(400)
                .doesNotHaveDuplicates();
    }

    @Test
    void rejectsNegativeTolerance() {
        assertThatThrownBy(() -> new Matcher(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
