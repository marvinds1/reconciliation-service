package io.github.marvinds1.reconciliation.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marvinds1.reconciliation.domain.Entry;
import io.github.marvinds1.reconciliation.domain.Entry.Source;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntryCsvParserTest {

    private final EntryCsvParser parser = new EntryCsvParser(100);

    private List<Entry> parse(String csv) {
        return parser.parse(Source.LEDGER, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void convertsDecimalAmountsToMinorUnitsPerCurrency() {
        List<Entry> entries = parse("""
                ﻿reference,value_date,amount,currency,description
                A-1,2026-11-02,-1500000.00,IDR,"Transfer, supplier"
                B-1,2026-11-03,12.5,USD,
                C-1,2026-11-03,1000,JPY,yen
                """);

        assertThat(entries).extracting(Entry::amountMinor).containsExactly(-150_000_000L, 1250L, 1000L);
        assertThat(entries.get(0).description()).isEqualTo("Transfer, supplier");
        assertThat(entries.get(0).valueDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(entries).extracting(Entry::line).containsExactly(2, 3, 4);
    }

    @Test
    void descriptionColumnIsOptional() {
        assertThat(parse("reference,value_date,amount,currency\nA,2026-11-02,1,IDR\n")).hasSize(1);
    }

    @Test
    void reportsEveryBadRowWithItsLineNumber() {
        assertThatThrownBy(() -> parse("""
                reference,value_date,amount,currency,description
                A,2026-11-02,1.234,IDR,too many decimals
                B,02/11/2026,1,IDR,bad date
                C,2026-11-02,abc,IDR,not a number
                D,2026-11-02,1,XXX1,bad currency
                E,2026-11-02,1,IDR,ok
                """))
                .isInstanceOfSatisfying(InvalidFileException.class, e -> assertThat(e.errors()).containsExactly(
                        "line 2: amount 1.234 has too many decimals for IDR",
                        "line 3: value_date must be YYYY-MM-DD",
                        "line 4: amount is not a number",
                        "line 5: unknown currency 'XXX1'"));
    }

    @Test
    void rejectsWrongHeader() {
        assertThatThrownBy(() -> parse("ref,date,amt\nA,2026-11-02,1\n"))
                .isInstanceOfSatisfying(InvalidFileException.class,
                        e -> assertThat(e.errors().get(0)).startsWith("header must be"));
    }

    @Test
    void enforcesRowLimit() {
        StringBuilder csv = new StringBuilder("reference,value_date,amount,currency\n");
        for (int i = 0; i < 101; i++) {
            csv.append("R").append(i).append(",2026-11-02,1,IDR\n");
        }

        assertThatThrownBy(() -> parse(csv.toString())).isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("more than 100 rows");
    }
}
