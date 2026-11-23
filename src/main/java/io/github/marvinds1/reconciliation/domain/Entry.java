package io.github.marvinds1.reconciliation.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One row from either side of a reconciliation. Amounts are signed minor units
 * (positive = credit to the account, negative = debit).
 */
public record Entry(
        Source source,
        int line,
        String reference,
        LocalDate valueDate,
        long amountMinor,
        String currency,
        String description) {

    public enum Source { LEDGER, STATEMENT }

    public Entry {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(currency, "currency");
        reference = reference == null ? "" : reference.strip();
        description = description == null ? "" : description.strip();
    }

    public boolean hasReference() {
        return !reference.isEmpty();
    }
}
