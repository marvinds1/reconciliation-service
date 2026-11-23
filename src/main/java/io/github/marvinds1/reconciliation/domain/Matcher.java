package io.github.marvinds1.reconciliation.domain;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic two-pass matcher.
 *
 * <ol>
 *   <li>Duplicate references inside one file are set aside as {@link MatchStatus#DUPLICATE}.</li>
 *   <li>Pass 1 pairs entries with the same reference.</li>
 *   <li>Pass 2 pairs leftovers with the same amount and currency whose value dates are within
 *       the tolerance (settlement often lands a day later), closest date first. Entries carrying
 *       two different, non-empty references are never paired this way.</li>
 *   <li>Whatever is left is missing on the other side.</li>
 * </ol>
 *
 * Every entry appears in exactly one result, and each pairing is one-to-one.
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
        List<MatchResult> results = new ArrayList<>();
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

        Map<String, List<Entry>> statementByAmount = new HashMap<>();
        statementPool.stream()
                .filter(s -> !pairedStatement.contains(s))
                .sorted(BY_DATE_THEN_LINE)
                .forEach(s -> statementByAmount.computeIfAbsent(amountKey(s), k -> new ArrayList<>()).add(s));

        unmatchedLedger.sort(BY_DATE_THEN_LINE);
        for (Entry l : unmatchedLedger) {
            Entry s = takeClosest(l, statementByAmount.getOrDefault(amountKey(l), List.of()));
            results.add(s != null
                    ? MatchResult.pair(MatchRule.AMOUNT_AND_DATE, l, s)
                    : MatchResult.single(MatchStatus.MISSING_IN_STATEMENT, l));
        }

        statementByAmount.values().stream().flatMap(List::stream)
                .sorted(BY_DATE_THEN_LINE)
                .forEach(s -> results.add(MatchResult.single(MatchStatus.MISSING_IN_LEDGER, s)));
        return results;
    }

    private static List<Entry> withoutDuplicates(List<Entry> entries, List<MatchResult> results) {
        Map<String, Entry> firstByReference = new LinkedHashMap<>();
        List<Entry> unique = new ArrayList<>();
        for (Entry e : entries) {
            if (e.hasReference() && firstByReference.putIfAbsent(e.reference(), e) != null) {
                results.add(MatchResult.single(MatchStatus.DUPLICATE, e));
            } else {
                unique.add(e);
            }
        }
        return unique;
    }

    private Entry takeClosest(Entry ledger, List<Entry> candidates) {
        Entry best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Entry s : candidates) {
            long distance = Math.abs(ChronoUnit.DAYS.between(ledger.valueDate(), s.valueDate()));
            if (distance <= dateToleranceDays && distance < bestDistance && !conflictingReferences(ledger, s)) {
                best = s;
                bestDistance = distance;
            }
        }
        if (best != null) {
            for (Iterator<Entry> it = candidates.iterator(); it.hasNext(); ) {
                if (it.next() == best) {
                    it.remove();
                    break;
                }
            }
        }
        return best;
    }

    private static boolean conflictingReferences(Entry a, Entry b) {
        return a.hasReference() && b.hasReference() && !a.reference().equals(b.reference());
    }

    private static String amountKey(Entry e) {
        return e.currency() + ':' + e.amountMinor();
    }
}
