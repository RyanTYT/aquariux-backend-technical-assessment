package com.aquariux.technical.assessment.trade.controller;

import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.service.TradeServiceInterface;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trades")
@Tag(name = "Trade", description = "Trading operations")
@RequiredArgsConstructor
public class TradeController {

    private final TradeServiceInterface tradeService;
    // Add additional beans here if needed for your implementation

    @PostMapping(value = "/execute", produces = "application/json")
    @Operation(summary = "Execute trade",
            description = "Execute a market trade for a supported cryptocurrency pair. BUY fills at the pair's best ask, SELL at its best bid; quantity is in the base currency (BTC/ETH), the USDT cost is quantity x price.")
    public ResponseEntity<TradeResponse> executeTrade(@Valid @RequestBody TradeRequest tradeRequest) {
        return ResponseEntity.ok(tradeService.executeTrade(tradeRequest));
    }
}
