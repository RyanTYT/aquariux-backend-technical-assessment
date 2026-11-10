package com.aquariux.technical.assessment.trade.dto.response;

import java.time.LocalDateTime;

/**
 * Consistent error payload returned by {@code GlobalExceptionHandler}.
 */
public record ErrorResponse(String title, int status, String error, String message, LocalDateTime timestamp) {

    public static ErrorResponse of(String title, int status, String error, String message) {
        return new ErrorResponse(title, status, error, message, LocalDateTime.now());
    }
}
