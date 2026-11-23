package io.github.marvinds1.reconciliation.domain;

public enum MatchStatus {
    /** Both sides agree on amount and currency. */
    MATCHED,
    /** Same reference, but amount or currency differs. */
    AMOUNT_MISMATCH,
    /** Booked internally, not seen on the bank statement. */
    MISSING_IN_STATEMENT,
    /** On the bank statement, not booked internally. */
    MISSING_IN_LEDGER,
    /** Reference already used earlier in the same file. */
    DUPLICATE;

    public boolean isException() {
        return this != MATCHED;
    }
}
