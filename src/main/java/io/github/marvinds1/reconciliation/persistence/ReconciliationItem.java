package io.github.marvinds1.reconciliation.persistence;

import io.github.marvinds1.reconciliation.domain.MatchRule;
import io.github.marvinds1.reconciliation.domain.MatchStatus;
import java.time.LocalDate;

public record ReconciliationItem(
        long id,
        MatchStatus status,
        MatchRule matchRule,
        String currency,
        Side ledger,
        Side statement,
        Long differenceMinor) {

    public record Side(int line, String reference, LocalDate valueDate, long amountMinor) {
    }
}
