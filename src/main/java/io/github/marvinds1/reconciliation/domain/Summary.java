package io.github.marvinds1.reconciliation.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record Summary(
        int ledgerEntries,
        int statementEntries,
        int matched,
        int amountMismatch,
        int missingInStatement,
        int missingInLedger,
        int duplicate) {

    public static Summary of(int ledgerEntries, int statementEntries, List<MatchResult> results) {
        Map<MatchStatus, Integer> counts = new EnumMap<>(MatchStatus.class);
        results.forEach(r -> counts.merge(r.status(), 1, Integer::sum));
        return new Summary(
                ledgerEntries,
                statementEntries,
                counts.getOrDefault(MatchStatus.MATCHED, 0),
                counts.getOrDefault(MatchStatus.AMOUNT_MISMATCH, 0),
                counts.getOrDefault(MatchStatus.MISSING_IN_STATEMENT, 0),
                counts.getOrDefault(MatchStatus.MISSING_IN_LEDGER, 0),
                counts.getOrDefault(MatchStatus.DUPLICATE, 0));
    }

    @JsonProperty
    public int exceptions() {
        return amountMismatch + missingInStatement + missingInLedger + duplicate;
    }
}
