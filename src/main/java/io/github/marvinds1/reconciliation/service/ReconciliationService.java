package io.github.marvinds1.reconciliation.service;

import io.github.marvinds1.reconciliation.csv.EntryCsvParser;
import io.github.marvinds1.reconciliation.domain.Entry;
import io.github.marvinds1.reconciliation.domain.MatchResult;
import io.github.marvinds1.reconciliation.domain.MatchStatus;
import io.github.marvinds1.reconciliation.domain.Matcher;
import io.github.marvinds1.reconciliation.domain.Summary;
import io.github.marvinds1.reconciliation.persistence.ReconciliationItem;
import io.github.marvinds1.reconciliation.persistence.ReconciliationRepository;
import io.github.marvinds1.reconciliation.persistence.ReconciliationRun;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReconciliationService {

    public static final int MAX_TOLERANCE_DAYS = 31;

    private final ReconciliationRepository repository;
    private final EntryCsvParser parser;
    private final int defaultToleranceDays;
    private final TransactionTemplate transaction;

    public ReconciliationService(ReconciliationRepository repository, PlatformTransactionManager transactionManager,
                                 @Value("${reconciliation.max-rows-per-file}") int maxRows,
                                 @Value("${reconciliation.default-date-tolerance-days}") int defaultToleranceDays) {
        this.repository = repository;
        this.parser = new EntryCsvParser(maxRows);
        this.defaultToleranceDays = defaultToleranceDays;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record Outcome(ReconciliationRun run, boolean replayed) {
    }

    /**
     * Reconciles two files. Uploading the same pair of files with the same tolerance again returns
     * the original run instead of creating a second one. Parsing and matching happen outside the
     * database transaction so large files do not hold a connection.
     */
    public Outcome reconcile(UploadedFile ledgerFile, UploadedFile statementFile, Integer toleranceDays) {
        int tolerance = toleranceDays == null ? defaultToleranceDays : toleranceDays;
        if (tolerance < 0 || tolerance > MAX_TOLERANCE_DAYS) {
            throw new IllegalArgumentException("dateToleranceDays must be between 0 and " + MAX_TOLERANCE_DAYS);
        }
        String hash = contentHash(ledgerFile.content(), statementFile.content(), tolerance);
        var existing = repository.findRunByHash(hash);
        if (existing.isPresent()) {
            return new Outcome(existing.get(), true);
        }

        List<Entry> ledger = parser.parse(Entry.Source.LEDGER, new ByteArrayInputStream(ledgerFile.content()));
        List<Entry> statement = parser.parse(Entry.Source.STATEMENT, new ByteArrayInputStream(statementFile.content()));
        List<MatchResult> results = new Matcher(tolerance).match(ledger, statement);
        Summary summary = Summary.of(ledger.size(), statement.size(), results);

        return transaction.execute(status -> {
            UUID id = UUID.randomUUID();
            boolean inserted = repository.insertRunIfAbsent(id, hash, ledgerFile.name(), statementFile.name(),
                    tolerance, summary);
            if (!inserted) {
                return new Outcome(repository.findRunByHash(hash).orElseThrow(), true);
            }
            repository.insertItems(id, results);
            return new Outcome(repository.findRun(id).orElseThrow(), false);
        });
    }

    @Transactional(readOnly = true)
    public ReconciliationRun get(UUID id) {
        return repository.findRun(id).orElseThrow(() -> new RunNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<ReconciliationItem> items(UUID runId, List<MatchStatus> statuses, long afterId, int limit) {
        get(runId);
        List<MatchStatus> filter = statuses == null || statuses.isEmpty() ? List.of(MatchStatus.values()) : statuses;
        return repository.findItems(runId, filter, afterId, limit);
    }

    static String contentHash(byte[] ledger, byte[] statement, int tolerance) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sha256(ledger));
            digest.update(sha256(statement));
            digest.update(Integer.toString(tolerance).getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}
