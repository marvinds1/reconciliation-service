package io.github.marvinds1.reconciliation.service;

import java.util.UUID;

public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(UUID id) {
        super("reconciliation run " + id + " not found");
    }
}
