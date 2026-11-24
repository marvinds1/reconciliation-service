package io.github.marvinds1.reconciliation.web;

import io.github.marvinds1.reconciliation.domain.MatchStatus;
import io.github.marvinds1.reconciliation.persistence.ReconciliationItem;
import io.github.marvinds1.reconciliation.persistence.ReconciliationRun;
import io.github.marvinds1.reconciliation.service.ReconciliationService;
import io.github.marvinds1.reconciliation.service.UploadedFile;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Validated
@RestController
@RequestMapping("/api/v1/reconciliations")
public class ReconciliationController {

    private static final int EXPORT_PAGE = 1000;
    private static final List<MatchStatus> EXCEPTION_STATUSES =
            Arrays.stream(MatchStatus.values()).filter(MatchStatus::isException).toList();

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReconciliationRun> create(
            @RequestPart("ledgerFile") MultipartFile ledgerFile,
            @RequestPart("statementFile") MultipartFile statementFile,
            @RequestParam(required = false) Integer dateToleranceDays) throws IOException {
        var outcome = service.reconcile(
                new UploadedFile(ledgerFile.getOriginalFilename(), ledgerFile.getBytes()),
                new UploadedFile(statementFile.getOriginalFilename(), statementFile.getBytes()),
                dateToleranceDays);
        if (outcome.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(outcome.run());
        }
        return ResponseEntity.created(URI.create("/api/v1/reconciliations/" + outcome.run().id()))
                .body(outcome.run());
    }

    @GetMapping("/{id}")
    public ReconciliationRun get(@PathVariable UUID id) {
        return service.get(id);
    }

    public record ItemPage(List<ReconciliationItem> items, Long nextAfterId) {
    }

    @GetMapping("/{id}/items")
    public ItemPage items(@PathVariable UUID id,
                          @RequestParam(required = false) List<MatchStatus> status,
                          @RequestParam(defaultValue = "0") @Min(0) long afterId,
                          @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        List<ReconciliationItem> items = service.items(id, status, afterId, limit);
        Long next = items.size() == limit ? items.get(items.size() - 1).id() : null;
        return new ItemPage(items, next);
    }

    @GetMapping(value = "/{id}/exceptions.csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exceptions(@PathVariable UUID id) {
        service.get(id);
        StreamingResponseBody body = out -> {
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            CSVPrinter csv = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(
                    "status", "currency", "ledger_line", "ledger_reference", "ledger_value_date",
                    "ledger_amount_minor", "statement_line", "statement_reference", "statement_value_date",
                    "statement_amount_minor", "difference_minor").get());
            long afterId = 0;
            List<ReconciliationItem> page;
            do {
                page = service.items(id, EXCEPTION_STATUSES, afterId, EXPORT_PAGE);
                for (ReconciliationItem item : page) {
                    var l = item.ledger();
                    var s = item.statement();
                    csv.printRecord(item.status(), item.currency(),
                            l == null ? null : l.line(), l == null ? null : spreadsheetSafe(l.reference()),
                            l == null ? null : l.valueDate(), l == null ? null : l.amountMinor(),
                            s == null ? null : s.line(), s == null ? null : spreadsheetSafe(s.reference()),
                            s == null ? null : s.valueDate(), s == null ? null : s.amountMinor(),
                            item.differenceMinor());
                    afterId = item.id();
                }
            } while (page.size() == EXPORT_PAGE);
            csv.flush();
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"exceptions-" + id + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    /**
     * References come from uploaded files and end up in a CSV opened by operations staff.
     * A value starting with = + - @ (or tab / CR) would be evaluated as a spreadsheet formula,
     * so it is prefixed with a quote (OWASP CSV injection guidance).
     */
    static String spreadsheetSafe(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }
}
