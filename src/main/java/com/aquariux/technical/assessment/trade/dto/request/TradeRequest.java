package com.aquariux.technical.assessment.trade.dto.request;

import com.aquariux.technical.assessment.trade.enums.TradeType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request for executing a market trade.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code quantity} is always denominated in the pair's <b>base</b> currency
 *       (BTC for BTCUSDT, ETH for ETHUSDT) for both BUY and SELL.</li>
 *   <li>The executed USDT cost is derived: BUY fills at the pair's best ask,
 *       SELL at the pair's best bid; totalAmount = quantity x price.</li>
 * </ul>
 */
@Data
public class TradeRequest {

    @NotNull(message = "userId is required")
    private Long userId;

    @NotNull(message = "tradeType is required")
    private TradeType tradeType;

    @NotBlank(message = "pairName is required")
    private String pairName;

    @NotNull(message = "quantity is required")
    @DecimalMin(value = "0", inclusive = false, message = "quantity must be greater than zero")
    @Digits(integer = 12, fraction = 8, message = "quantity supports at most 12 integer and 8 fractional digits")
    private BigDecimal quantity;
}
