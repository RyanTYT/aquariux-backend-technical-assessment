package com.aquariux.technical.assessment.trade.exception;

import org.springframework.http.HttpStatus;

public class InsufficientBalanceException extends TradeException {

    public InsufficientBalanceException() {
        super(HttpStatus.BAD_REQUEST, "Insufficient balance for the requested trade");
    }
}
