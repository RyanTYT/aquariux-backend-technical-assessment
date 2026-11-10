package com.aquariux.technical.assessment.trade.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base type for all business exceptions raised by the trading system.
 *
 * <p>Subclasses carry their own HTTP status so that the single
 * {@link GlobalExceptionHandler} for {@code TradeException} can map each
 * exception to the right response without duplicating status resolution.
 */
@Getter
public abstract class TradeException extends RuntimeException {

    private final HttpStatus status;

    protected TradeException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
