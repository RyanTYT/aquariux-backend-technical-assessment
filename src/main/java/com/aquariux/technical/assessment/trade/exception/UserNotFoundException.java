package com.aquariux.technical.assessment.trade.exception;

import org.springframework.http.HttpStatus;

public class UserNotFoundException extends TradeException {

    public UserNotFoundException(Long userId) {
        super(HttpStatus.NOT_FOUND, "User not found: %d".formatted(userId));
    }
}
