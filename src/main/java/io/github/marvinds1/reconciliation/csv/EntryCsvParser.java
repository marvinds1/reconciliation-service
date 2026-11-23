package io.github.marvinds1.reconciliation.csv;

import io.github.marvinds1.reconciliation.domain.Entry;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.input.BOMInputStream;

/**
 * Parses {@code reference,value_date,amount,currency,description} files.
 * Amounts are decimal strings ("-150000.00") converted to minor units using the
 * currency's ISO 4217 exponent; more decimals than the currency allows is an error.
 */
public final class EntryCsvParser {

    static final List<String> HEADER = List.of("reference", "value_date", "amount", "currency", "description");
    private static final int MAX_REPORTED_ERRORS = 20;
    private static final int MAX_REFERENCE_LENGTH = 128;

    private final int maxRows;

    public EntryCsvParser(int maxRows) {
        this.maxRows = maxRows;
    }

    public List<Entry> parse(Entry.Source source, InputStream input) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true)
                .get();
        try (Reader reader = new InputStreamReader(BOMInputStream.builder().setInputStream(input).get(), StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.builder().setReader(reader).setFormat(format).get()) {
            checkHeader(source, parser.getHeaderNames());
            List<Entry> entries = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (CSVRecord row : parser) {
                if (entries.size() + errors.size() >= maxRows) {
                    throw new InvalidFileException(source, List.of("more than " + maxRows + " rows"));
                }
                int line = (int) row.getRecordNumber() + 1;
                try {
                    entries.add(toEntry(source, line, row));
                } catch (IllegalArgumentException | ArithmeticException | DateTimeParseException e) {
                    errors.add("line " + line + ": " + message(e));
                }
            }
            if (!errors.isEmpty()) {
                throw new InvalidFileException(source, errors.subList(0, Math.min(errors.size(), MAX_REPORTED_ERRORS)));
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new InvalidFileException(source, List.of("malformed CSV: " + e.getMessage()));
        }
    }

    private static void checkHeader(Entry.Source source, List<String> header) {
        if (!Set.copyOf(header).containsAll(HEADER.subList(0, 4))) {
            throw new InvalidFileException(source, List.of("header must be " + String.join(",", HEADER)));
        }
    }

    private static Entry toEntry(Entry.Source source, int line, CSVRecord row) {
        if (!row.isConsistent()) {
            throw new IllegalArgumentException("expected " + row.getParser().getHeaderNames().size() + " columns");
        }
        if (row.get("reference").length() > MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException("reference longer than " + MAX_REFERENCE_LENGTH + " characters");
        }
        String currencyCode = required(row, "currency");
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown currency '" + currencyCode + "'");
        }
        BigDecimal amount = new BigDecimal(required(row, "amount"));
        long minor;
        try {
            minor = amount.movePointRight(currency.getDefaultFractionDigits()).longValueExact();
        } catch (ArithmeticException e) {
            throw new ArithmeticException("amount " + amount + " has too many decimals for " + currencyCode);
        }
        String description = row.isMapped("description") ? row.get("description") : "";
        return new Entry(source, line, row.get("reference"), LocalDate.parse(required(row, "value_date")),
                minor, currencyCode, description);
    }

    private static String required(CSVRecord row, String column) {
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(column + " is required");
        }
        return value;
    }

    private static String message(RuntimeException e) {
        if (e instanceof NumberFormatException) {
            return "amount is not a number";
        }
        if (e instanceof DateTimeParseException) {
            return "value_date must be YYYY-MM-DD";
        }
        return e.getMessage();
    }
}
