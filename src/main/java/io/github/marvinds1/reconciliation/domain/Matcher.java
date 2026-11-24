package io.github.marvinds1.reconciliation.domain;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Deterministic two-pass matcher.
 *
 * <ol>
 *   <li>Duplicate references inside one file are set aside as {@link MatchStatus#DUPLICATE}.</li>
 *   <li>Pass 1 pairs entries with the same reference.</li>
 *   <li>Pass 2 pairs leftovers with the same amount and currency whose value dates are within
 *       the tolerance (settlement often lands a day later), closest date first, earlier date on
 *       a tie. Entries carrying two different, non-empty references are never paired this way.</li>
 *   <li>Whatever is left is missing on the other side.</li>
 * </ol>
 *
 * Every entry appears in exactly one result, and each pairing is one-to-one.
 *
 * <p>Pass 2 indexes candidates by amount and then by date, so each lookup probes at most
 * {@code 2 × tolerance + 1} dates instead of scanning every entry with the same amount.
 * Files full of identical amounts (fees, standing orders) stay roughly linear.
 */
public final class Matcher {

    private static final Comparator<Entry> BY_DATE_THEN_LINE =
            Comparator.comparing(Entry::valueDate).thenComparingInt(Entry::line);

    private final int dateToleranceDays;

    public Matcher(int dateToleranceDays) {
        if (dateToleranceDays < 0) {
            throw new IllegalArgumentException("dateToleranceDays must be >= 0");
        }
        this.dateToleranceDays = dateToleranceDays;
    }

    public List<MatchResult> match(List<Entry> ledger, List<Entry> statement) {
        List<MatchResult> results = new ArrayList<>(ledger.size() + statement.size());
        List<Entry> ledgerPool = withoutDuplicates(ledger, results);
        List<Entry> statementPool = withoutDuplicates(statement, results);

        Map<String, Entry> statementByReference = new HashMap<>();
        statementPool.stream().filter(Entry::hasReference)
                .forEach(e -> statementByReference.put(e.reference(), e));

        List<Entry> unmatchedLedger = new ArrayList<>();
        Set<Entry> pairedStatement = new HashSet<>();
        for (Entry l : ledgerPool) {
            Entry s = l.hasReference() ? statementByReference.remove(l.reference()) : null;
            if (s != null) {
                results.add(MatchResult.pair(MatchRule.REFERENCE, l, s));
                pairedStatement.add(s);
            } else {
                unmatchedLedger.add(l);
            }
        }

        Map<String, Map<LocalDate, DayBucket>> candidates = new HashMap<>();
        statementPool.stream()
                .filter(s -> !pairedStatement.contains(s))
                .sorted(BY_DATE_THEN_LINE)
                .forEach(s -> candidates.computeIfAbsent(amountKey(s), k -> new HashMap<>())
                        .computeIfAbsent(s.valueDate(), d -> new DayBucket())
                        .add(s));

        unmatchedLedger.sort(BY_DATE_THEN_LINE);
        for (Entry l : unmatchedLedger) {
            Entry s = takeClosest(l, candidates.get(amountKey(l)));
            results.add(s != null
                    ? MatchResult.pair(MatchRule.AMOUNT_AND_DATE, l, s)
                    : MatchResult.single(MatchStatus.MISSING_IN_STATEMENT, l));
        }

        candidates.values().stream()
                .flatMap(byDate -> byDate.values().stream())
                .flatMap(DayBucket::remaining)
                .sorted(BY_DATE_THEN_LINE)
                .forEach(s -> results.add(MatchResult.single(MatchStatus.MISSING_IN_LEDGER, s)));
        return results;
    }

    private static List<Entry> withoutDuplicates(List<Entry> entries, List<MatchResult> results) {
        Map<String, Entry> firstByReference = new LinkedHashMap<>();
        List<Entry> unique = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (e.hasReference() && firstByReference.putIfAbsent(e.reference(), e) != null) {
                results.add(MatchResult.single(MatchStatus.DUPLICATE, e));
            } else {
                unique.add(e);
            }
        }
        return unique;
    }

    /** Probes dates by increasing distance (earlier date first on a tie) and removes the hit. */
    private Entry takeClosest(Entry ledger, Map<LocalDate, DayBucket> byDate) {
        if (byDate == null || byDate.isEmpty()) {
            return null;
        }
        for (int distance = 0; distance <= dateToleranceDays; distance++) {
            Entry hit = take(byDate, ledger, ledger.valueDate().minusDays(distance));
            if (hit == null && distance > 0) {
                hit = take(byDate, ledger, ledger.valueDate().plusDays(distance));
            }
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Entry take(Map<LocalDate, DayBucket> byDate, Entry ledger, LocalDate date) {
        DayBucket bucket = byDate.get(date);
        return bucket == null ? null : bucket.take(ledger.hasReference());
    }

    /**
     * Unpaired statement entries for one amount and date, split by whether they carry a
     * reference. A referenced ledger entry left after pass 1 has no statement entry with the same
     * reference, so it may only pair with unreferenced ones; that makes every take O(1).
     */
    private static final class DayBucket {
        private final Deque<Entry> withoutReference = new ArrayDeque<>();
        private final Deque<Entry> withReference = new ArrayDeque<>();

        void add(Entry e) {
            (e.hasReference() ? withReference : withoutReference).addLast(e);
        }

        Entry take(boolean ledgerHasReference) {
            if (ledgerHasReference) {
                return withoutReference.pollFirst();
            }
            Entry a = withoutReference.peekFirst();
            Entry b = withReference.peekFirst();
            if (a == null) {
                return withReference.pollFirst();
            }
            if (b == null || a.line() < b.line()) {
                return withoutReference.pollFirst();
            }
            return withReference.pollFirst();
        }

        Stream<Entry> remaining() {
            return Stream.concat(withoutReference.stream(), withReference.stream());
        }
    }

    private static String amountKey(Entry e) {
        return e.currency() + ':' + e.amountMinor();
    }
}
