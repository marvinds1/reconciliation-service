package io.github.marvinds1.reconciliation.domain;

public enum MatchRule {
    /** Paired by identical reference. */
    REFERENCE,
    /** Paired by identical amount and currency with value dates within the tolerance. */
    AMOUNT_AND_DATE
}
