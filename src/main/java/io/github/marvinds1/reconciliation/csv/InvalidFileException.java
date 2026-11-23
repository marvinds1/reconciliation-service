package io.github.marvinds1.reconciliation.csv;

import io.github.marvinds1.reconciliation.domain.Entry;
import java.util.List;

public class InvalidFileException extends RuntimeException {

    private final Entry.Source source;
    private final List<String> errors;

    public InvalidFileException(Entry.Source source, List<String> errors) {
        super(source.name().toLowerCase() + " file is invalid: " + String.join("; ", errors));
        this.source = source;
        this.errors = List.copyOf(errors);
    }

    public Entry.Source source() {
        return source;
    }

    public List<String> errors() {
        return errors;
    }
}
