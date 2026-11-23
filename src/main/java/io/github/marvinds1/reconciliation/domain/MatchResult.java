package io.github.marvinds1.reconciliation.domain;

/**
 * Outcome for one ledger entry, one statement entry, or a pair of them.
 * {@code ledger} or {@code statement} is null when that side is absent.
 */
public record MatchResult(MatchStatus status, MatchRule rule, Entry ledger, Entry statement) {

    public static MatchResult pair(MatchRule rule, Entry ledger, Entry statement) {
        boolean agrees = ledger.amountMinor() == statement.amountMinor()
                && ledger.currency().equals(statement.currency());
        return new MatchResult(agrees ? MatchStatus.MATCHED : MatchStatus.AMOUNT_MISMATCH, rule, ledger, statement);
    }

    public static MatchResult single(MatchStatus status, Entry entry) {
        return entry.source() == Entry.Source.LEDGER
                ? new MatchResult(status, null, entry, null)
                : new MatchResult(status, null, null, entry);
    }

    public String currency() {
        return ledger != null ? ledger.currency() : statement.currency();
    }

    /** Statement minus ledger, only meaningful for a pair in the same currency. */
    public Long differenceMinor() {
        if (ledger == null || statement == null || !ledger.currency().equals(statement.currency())) {
            return null;
        }
        return statement.amountMinor() - ledger.amountMinor();
    }
}
