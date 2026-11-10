package com.aquariux.technical.assessment.trade.exception;

import org.springframework.http.HttpStatus;

public class CryptoPairNotFoundException extends TradeException {

    public CryptoPairNotFoundException(String pairName) {
        super(HttpStatus.NOT_FOUND, "Trading pair not found or not supported: %s".formatted(pairName));
    }
}
