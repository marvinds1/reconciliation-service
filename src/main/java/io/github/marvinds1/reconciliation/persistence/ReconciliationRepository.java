package io.github.marvinds1.reconciliation.persistence;

import io.github.marvinds1.reconciliation.domain.Entry;
import io.github.marvinds1.reconciliation.domain.MatchResult;
import io.github.marvinds1.reconciliation.domain.MatchRule;
import io.github.marvinds1.reconciliation.domain.MatchStatus;
import io.github.marvinds1.reconciliation.domain.Summary;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationRepository {

    private static final int BATCH_SIZE = 1000;

    private final JdbcTemplate jdbc;

    public ReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the run unless one with the same content hash exists; returns whether it was inserted. */
    public boolean insertRunIfAbsent(UUID id, String contentHash, String ledgerFileName, String statementFileName,
                                     int toleranceDays, Summary s) {
        int inserted = jdbc.update("""
                INSERT INTO reconciliation_run (id, content_hash, ledger_file_name, statement_file_name,
                    date_tolerance_days, ledger_entries, statement_entries, matched, amount_mismatch,
                    missing_in_statement, missing_in_ledger, duplicate)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (content_hash) DO NOTHING
                """, id, contentHash, ledgerFileName, statementFileName, toleranceDays, s.ledgerEntries(),
                s.statementEntries(), s.matched(), s.amountMismatch(), s.missingInStatement(),
                s.missingInLedger(), s.duplicate());
        return inserted == 1;
    }

    public void insertItems(UUID runId, List<MatchResult> results) {
        jdbc.batchUpdate("""
                INSERT INTO reconciliation_item (run_id, status, match_rule, currency,
                    ledger_line, ledger_reference, ledger_value_date, ledger_amount_minor,
                    statement_line, statement_reference, statement_value_date, statement_amount_minor,
                    difference_minor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, results, BATCH_SIZE, (ps, r) -> {
            ps.setObject(1, runId);
            ps.setString(2, r.status().name());
            ps.setString(3, r.rule() == null ? null : r.rule().name());
            ps.setString(4, r.currency());
            bindSide(ps, 5, r.ledger());
            bindSide(ps, 9, r.statement());
            ps.setObject(13, r.differenceMinor(), Types.BIGINT);
        });
    }

    public Optional<ReconciliationRun> findRun(UUID id) {
        return jdbc.query("SELECT * FROM reconciliation_run WHERE id = ?", this::mapRun, id).stream().findFirst();
    }

    public Optional<ReconciliationRun> findRunByHash(String contentHash) {
        return jdbc.query("SELECT * FROM reconciliation_run WHERE content_hash = ?", this::mapRun, contentHash)
                .stream().findFirst();
    }

    public List<ReconciliationItem> findItems(UUID runId, List<MatchStatus> statuses, long afterId, int limit) {
        String[] names = statuses.stream().map(Enum::name).toArray(String[]::new);
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    SELECT * FROM reconciliation_item
                    WHERE run_id = ? AND status = ANY (?) AND id > ?
                    ORDER BY id
                    LIMIT ?
                    """);
            ps.setObject(1, runId);
            ps.setArray(2, con.createArrayOf("varchar", names));
            ps.setLong(3, afterId);
            ps.setInt(4, limit);
            return ps;
        }, this::mapItem);
    }

    private static void bindSide(PreparedStatement ps, int index, Entry e) throws SQLException {
        ps.setObject(index, e == null ? null : e.line(), Types.INTEGER);
        ps.setString(index + 1, e == null || !e.hasReference() ? null : e.reference());
        ps.setObject(index + 2, e == null ? null : Date.valueOf(e.valueDate()), Types.DATE);
        ps.setObject(index + 3, e == null ? null : e.amountMinor(), Types.BIGINT);
    }

    private ReconciliationRun mapRun(ResultSet rs, int row) throws SQLException {
        return new ReconciliationRun(
                rs.getObject("id", UUID.class),
                rs.getString("ledger_file_name"),
                rs.getString("statement_file_name"),
                rs.getInt("date_tolerance_days"),
                new Summary(rs.getInt("ledger_entries"), rs.getInt("statement_entries"), rs.getInt("matched"),
                        rs.getInt("amount_mismatch"), rs.getInt("missing_in_statement"),
                        rs.getInt("missing_in_ledger"), rs.getInt("duplicate")),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private ReconciliationItem mapItem(ResultSet rs, int row) throws SQLException {
        String rule = rs.getString("match_rule");
        return new ReconciliationItem(
                rs.getLong("id"),
                MatchStatus.valueOf(rs.getString("status")),
                rule == null ? null : MatchRule.valueOf(rule),
                rs.getString("currency"),
                side(rs, "ledger_"),
                side(rs, "statement_"),
                rs.getObject("difference_minor", Long.class));
    }

    private static ReconciliationItem.Side side(ResultSet rs, String prefix) throws SQLException {
        Integer line = rs.getObject(prefix + "line", Integer.class);
        if (line == null) {
            return null;
        }
        return new ReconciliationItem.Side(line, rs.getString(prefix + "reference"),
                rs.getObject(prefix + "value_date", java.time.LocalDate.class), rs.getLong(prefix + "amount_minor"));
    }
}
