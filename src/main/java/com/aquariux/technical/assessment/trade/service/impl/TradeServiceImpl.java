package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.cache.LatestPriceCache;
import com.aquariux.technical.assessment.trade.dto.internal.PairSymbolsDto;
import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.entity.Trade;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.exception.CryptoPairNotFoundException;
import com.aquariux.technical.assessment.trade.exception.InvalidTradeRequestException;
import com.aquariux.technical.assessment.trade.exception.PriceNotAvailableException;
import com.aquariux.technical.assessment.trade.exception.UserNotFoundException;
import com.aquariux.technical.assessment.trade.mapper.CryptoPairMapper;
import com.aquariux.technical.assessment.trade.mapper.TradeMapper;
import com.aquariux.technical.assessment.trade.mapper.UserMapper;
import com.aquariux.technical.assessment.trade.service.TradeServiceInterface;
import com.aquariux.technical.assessment.trade.service.WalletServiceInterface;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Market-order trade execution engine.
 *
 * <p>Execution model (broker-style, no order book): the latest aggregated best
 * price stored by the price scheduler (served from an in-memory cache that
 * the scheduler keeps fresh) is used directly. BUY fills at the pair's
 * best ask, SELL at the pair's best bid; the full requested quantity is filled
 * at that single price (implicit infinite liquidity).
 *
 * <p>Consistency: the whole execution runs in one transaction. Wallet rows are
 * mutated in canonical ascending symbol-id order (deadlock prevention between
 * opposing trades of the same pair); the debit itself is a single guarded SQL
 * UPDATE that doubles as the balance check (race-safe under READ COMMITTED).
 * Any failure rolls the entire trade back, keeping wallets and the trades
 * ledger consistent.
 */
@Service
@RequiredArgsConstructor
public class TradeServiceImpl implements TradeServiceInterface {

    /** Column scale of all money/quantity columns (DECIMAL(20,8)). */
    private static final int AMOUNT_SCALE = 8;

    private final UserMapper userMapper;
    private final CryptoPairMapper cryptoPairMapper;
    private final LatestPriceCache latestPriceCache;
    private final TradeMapper tradeMapper;
    private final WalletServiceInterface walletService;

    @Override
    @Transactional
    public TradeResponse executeTrade(TradeRequest tradeRequest) {
        validate(tradeRequest);

        if (!userMapper.existsById(tradeRequest.getUserId())) {
            throw new UserNotFoundException(tradeRequest.getUserId());
        }

        PairSymbolsDto pair = cryptoPairMapper.findActiveByPairName(tradeRequest.getPairName().trim().toUpperCase());
        if (pair == null) {
            throw new CryptoPairNotFoundException(tradeRequest.getPairName());
        }

        CryptoPrice price = latestPriceCache.getLatestForPair(pair.getPairId());
        if (price == null) {
            throw new PriceNotAvailableException(pair.getPairName());
        }

        BigDecimal unitPrice = executionPrice(tradeRequest.getTradeType(), price);
        BigDecimal totalAmount = tradeRequest.getQuantity()
                .multiply(unitPrice)
                .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);

        // Wallet rows are always mutated in ascending symbol-id order
        // (canonical lock ordering). Opposing trades of the same pair would
        // otherwise lock quote->base (BUY: pay USDT, get BTC) vs base->quote
        // (SELL: deliver BTC, receive USDT) — opposite lock orders that
        // deadlock under concurrency. Inside this transaction the operation
        // order does not change the outcome: any failure rolls everything
        // back, and the guarded debit keeps the no-over-draw guarantee
        // wherever it runs.
        Runnable baseSide;
        Runnable quoteSide;
        if (tradeRequest.getTradeType() == TradeType.BUY) {
            // BUY: gets the base asset (first acquisition creates its wallet row),
            // pays the quote currency worth totalAmount.
            baseSide = () -> walletService.credit(tradeRequest.getUserId(), pair.getBaseSymbolId(), tradeRequest.getQuantity());
            quoteSide = () -> walletService.debit(tradeRequest.getUserId(), pair.getQuoteSymbolId(), totalAmount);
        } else {
            // SELL: delivers the base asset, receives quote currency.
            baseSide = () -> walletService.debit(tradeRequest.getUserId(), pair.getBaseSymbolId(), tradeRequest.getQuantity());
            quoteSide = () -> walletService.credit(tradeRequest.getUserId(), pair.getQuoteSymbolId(), totalAmount);
        }
        if (pair.getBaseSymbolId() < pair.getQuoteSymbolId()) {
            baseSide.run();
            quoteSide.run();
        } else {
            quoteSide.run();
            baseSide.run();
        }

        // 3) Record the immutable audit trail.
        Trade trade = new Trade();
        trade.setUserId(tradeRequest.getUserId());
        trade.setCryptoPairId(pair.getPairId());
        trade.setTradeType(tradeRequest.getTradeType().name());
        trade.setQuantity(tradeRequest.getQuantity());
        trade.setPrice(unitPrice);
        trade.setTotalAmount(totalAmount);
        trade.setTradeTime(LocalDateTime.now());
        tradeMapper.insertTrade(trade);

        return buildResponse(trade, pair.getPairName(), tradeRequest.getTradeType());
    }

    private void validate(TradeRequest tradeRequest) {
        // Bean validation covers the REST entry point; these checks keep the
        // service safe when invoked programmatically.
        if (tradeRequest == null) {
            throw new InvalidTradeRequestException("Trade request is required");
        }
        if (tradeRequest.getUserId() == null) {
            throw new InvalidTradeRequestException("userId is required");
        }
        if (tradeRequest.getTradeType() == null) {
            throw new InvalidTradeRequestException("tradeType is required");
        }
        if (tradeRequest.getPairName() == null || tradeRequest.getPairName().isBlank()) {
            throw new InvalidTradeRequestException("pairName is required");
        }
        if (tradeRequest.getQuantity() == null || tradeRequest.getQuantity().signum() <= 0) {
            throw new InvalidTradeRequestException("quantity must be greater than zero");
        }
    }

    /**
     * Market-order pricing: BUY consumes the aggregate best ask, SELL the best bid.
     */
    private BigDecimal executionPrice(TradeType tradeType, CryptoPrice price) {
        BigDecimal unitPrice = tradeType == TradeType.BUY ? price.getAskPrice() : price.getBidPrice();
        if (unitPrice == null || unitPrice.signum() <= 0) {
            throw new PriceNotAvailableException(price.getPairName());
        }
        return unitPrice;
    }

    private TradeResponse buildResponse(Trade trade, String pairName, TradeType tradeType) {
        TradeResponse response = new TradeResponse();
        response.setTradeId(trade.getId());
        response.setPairName(pairName);
        response.setTradeType(tradeType);
        response.setQuantity(trade.getQuantity());
        response.setPrice(trade.getPrice());
        response.setTotalAmount(trade.getTotalAmount());
        response.setTradeTime(trade.getTradeTime());
        return response;
    }
}
