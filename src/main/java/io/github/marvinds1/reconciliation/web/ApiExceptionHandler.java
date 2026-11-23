package io.github.marvinds1.reconciliation.web;

import io.github.marvinds1.reconciliation.csv.InvalidFileException;
import io.github.marvinds1.reconciliation.service.RunNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidFileException.class)
    ProblemDetail invalidFile(InvalidFileException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "INVALID_FILE",
                e.source().name().toLowerCase() + " file could not be parsed");
        problem.setProperty("file", e.source().name());
        problem.setProperty("errors", e.errors());
        return problem;
    }

    @ExceptionHandler(RunNotFoundException.class)
    ProblemDetail notFound(RunNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class})
    ProblemDetail badRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
