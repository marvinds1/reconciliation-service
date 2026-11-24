package io.github.marvinds1.reconciliation.service;

public class TooManyRunsException extends RuntimeException {

    public TooManyRunsException(int limit) {
        super("too many reconciliations in progress (limit " + limit + "), retry shortly");
    }
}
