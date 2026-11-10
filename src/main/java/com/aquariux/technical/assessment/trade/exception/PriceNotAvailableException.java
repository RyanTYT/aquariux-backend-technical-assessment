package com.aquariux.technical.assessment.trade.exception;

import org.springframework.http.HttpStatus;

public class PriceNotAvailableException extends TradeException {

    public PriceNotAvailableException(String pairName) {
        super(HttpStatus.SERVICE_UNAVAILABLE,
                "No market price currently available for trading pair: %s".formatted(pairName));
    }
}
