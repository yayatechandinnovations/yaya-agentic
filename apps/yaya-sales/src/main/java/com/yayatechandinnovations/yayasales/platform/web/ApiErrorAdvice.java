package com.yayatechandinnovations.yayasales.platform.web;

import com.yayatechandinnovations.yayasales.platform.concurrency.OptimisticRetry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiErrorAdvice {

    @ExceptionHandler(ApiErrors.NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ApiErrors.NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ApiErrors.ConflictException.class)
    ResponseEntity<Map<String, String>> conflict(ApiErrors.ConflictException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ApiErrors.UnprocessableException.class)
    ResponseEntity<Map<String, String>> unprocessable(ApiErrors.UnprocessableException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(OptimisticRetry.ConflictedException.class)
    ResponseEntity<Map<String, String>> lostRace(OptimisticRetry.ConflictedException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? "" : message));
    }
}
