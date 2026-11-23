package io.github.marvinds1.reconciliation.persistence;

import io.github.marvinds1.reconciliation.domain.Summary;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ReconciliationRun(
        UUID id,
        String ledgerFileName,
        String statementFileName,
        int dateToleranceDays,
        Summary summary,
        OffsetDateTime createdAt) {
}
