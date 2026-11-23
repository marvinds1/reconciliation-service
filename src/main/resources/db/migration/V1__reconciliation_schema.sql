CREATE TABLE reconciliation_run (
    id                    UUID PRIMARY KEY,
    content_hash          CHAR(64)     NOT NULL UNIQUE,
    ledger_file_name      VARCHAR(255) NOT NULL,
    statement_file_name   VARCHAR(255) NOT NULL,
    date_tolerance_days   INT          NOT NULL CHECK (date_tolerance_days BETWEEN 0 AND 31),
    ledger_entries        INT          NOT NULL,
    statement_entries     INT          NOT NULL,
    matched               INT          NOT NULL,
    amount_mismatch       INT          NOT NULL,
    missing_in_statement  INT          NOT NULL,
    missing_in_ledger     INT          NOT NULL,
    duplicate             INT          NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE reconciliation_item (
    id                      BIGSERIAL PRIMARY KEY,
    run_id                  UUID        NOT NULL REFERENCES reconciliation_run (id) ON DELETE CASCADE,
    status                  VARCHAR(32) NOT NULL CHECK (status IN
                                ('MATCHED', 'AMOUNT_MISMATCH', 'MISSING_IN_STATEMENT', 'MISSING_IN_LEDGER', 'DUPLICATE')),
    match_rule              VARCHAR(32) CHECK (match_rule IN ('REFERENCE', 'AMOUNT_AND_DATE')),
    currency                CHAR(3)     NOT NULL,
    ledger_line             INT,
    ledger_reference        VARCHAR(128),
    ledger_value_date       DATE,
    ledger_amount_minor     BIGINT,
    statement_line          INT,
    statement_reference     VARCHAR(128),
    statement_value_date    DATE,
    statement_amount_minor  BIGINT,
    difference_minor        BIGINT,
    CHECK (ledger_line IS NOT NULL OR statement_line IS NOT NULL)
);

CREATE INDEX idx_item_run_status ON reconciliation_item (run_id, status, id);
