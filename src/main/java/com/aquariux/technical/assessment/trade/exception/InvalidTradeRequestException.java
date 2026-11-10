package com.aquariux.technical.assessment.trade.exception;

import org.springframework.http.HttpStatus;

public class InvalidTradeRequestException extends TradeException {

    public InvalidTradeRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
