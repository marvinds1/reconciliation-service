package io.github.marvinds1.reconciliation.service;

public record UploadedFile(String name, byte[] content) {

    public UploadedFile {
        name = name == null || name.isBlank() ? "upload.csv" : name.strip();
        if (name.length() > 255) {
            name = name.substring(0, 255);
        }
    }
}
