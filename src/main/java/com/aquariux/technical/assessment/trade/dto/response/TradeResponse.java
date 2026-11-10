package com.aquariux.technical.assessment.trade.dto.response;

import com.aquariux.technical.assessment.trade.enums.TradeType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Result of an executed market trade.
 */
@Data
public class TradeResponse {

    /** Generated id of the persisted trade record. */
    private Long tradeId;

    private String pairName;

    private TradeType tradeType;

    /** Base-currency quantity that was bought or sold (BTC/ETH). */
    private BigDecimal quantity;

    /** Market price at execution: best ask for BUY, best bid for SELL. */
    private BigDecimal price;

    /** Quote-currency (USDT) amount moved: quantity x price. */
    private BigDecimal totalAmount;

    private LocalDateTime tradeTime;
}
