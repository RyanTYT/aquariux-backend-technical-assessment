package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.entity.Trade;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.mapper.TradeMapper;
import com.aquariux.technical.assessment.trade.scheduler.PriceUpdateScheduler;
import com.aquariux.technical.assessment.trade.service.TradeServiceInterface;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Fault-injection proof of partial-failure atomicity.
 *
 * <p>Every failed trade in the concurrency suite is rejected at the wallet
 * step <em>before</em> any other mutation, so transactional rollback is argued
 * by design there — never actually exercised. These tests inject a fault at
 * the <em>last</em> step (the immutable trade-ledger insert), after the wallet
 * mutations have already executed inside the transaction, and assert the
 * end-state: debit, credit and the lazy wallet-row creation must all have
 * rolled back, leaving the ledgers exactly as they were.
 *
 * <p>H2 on the class path means "no row" assertions are also cheap sanity
 * checks that no partial state survived the rollback.
 */
@SpringBootTest
class TradeExecutionRollbackTest {

    private static final String INJECTED_FAULT = "injected trade-ledger failure";

    @Autowired
    private TradeServiceInterface tradeService;
    @MockitoSpyBean
    private TradeMapper tradeMapper;
    @MockitoBean
    private PriceUpdateScheduler priceUpdateScheduler;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSpyAndSeedData() {
        // Undo any fault stubbing from a previous test so the spy delegates
        // to the real mapper again, then restore a minimal deterministic seed.
        reset(tradeMapper);

        jdbcTemplate.update("DELETE FROM trades");
        jdbcTemplate.update("DELETE FROM crypto_prices");
        jdbcTemplate.update("DELETE FROM user_wallets");
        jdbcTemplate.update("""
                INSERT INTO user_wallets (user_id, symbol_id, balance, updated_at) VALUES
                (1, 3, 80, CURRENT_TIMESTAMP),
                (7, 1, 0.5, CURRENT_TIMESTAMP),
                (7, 3, 20000, CURRENT_TIMESTAMP)
                """);
        // BTCUSDT: bid 90 / ask 100.
        jdbcTemplate.update("""
                INSERT INTO crypto_prices (crypto_pair_id, bid_price, ask_price, bid_source, ask_source) VALUES
                (1, 90, 100, 'TEST', 'TEST')
                """);
    }

    @Test
    @DisplayName("BUY fails at the ledger write: BTC credit, USDT debit and lazy wallet creation all roll back")
    void buyFailsAtomicallyWhenLedgerWriteFails() {
        doThrow(new RuntimeException(INJECTED_FAULT))
                .when(tradeMapper).insertTrade(any(Trade.class));

        // BUY 0.01 BTC @ ask 100 = 1.00 USDT out. Canonically ordered for
        // BTCUSDT (base id 1 < quote id 3): the BTC credit runs first, then
        // the USDT debit — both before the faulty ledger insert.
        TradeRequest buy = request(1, TradeType.BUY, "BTCUSDT", "0.01");

        assertThatThrownBy(() -> tradeService.executeTrade(buy))
                .hasMessage(INJECTED_FAULT);

        assertThat(tradeCount()).isZero();       // ledger write never committed
        assertThat(balance(1, 3)).isEqualByComparingTo("80"); // USDT untouched despite the in-tx debit
        assertThat(walletRowExists(1, 1)).isFalse();          // lazy BTC row creation rolled back
    }

    @Test
    @DisplayName("SELL fails at the ledger write: base debit and quote credit both roll back")
    void sellFailsAtomicallyWhenLedgerWriteFails() {
        doThrow(new RuntimeException(INJECTED_FAULT))
                .when(tradeMapper).insertTrade(any(Trade.class));

        // SELL 0.01 BTC @ bid 90 = 0.90 USDT in. Proves the rollback covers
        // the debit-first direction of the canonical ordering as well.
        TradeRequest sell = request(7, TradeType.SELL, "BTCUSDT", "0.01");

        assertThatThrownBy(() -> tradeService.executeTrade(sell))
                .hasMessage(INJECTED_FAULT);

        assertThat(tradeCount()).isZero();
        assertThat(balance(7, 1)).isEqualByComparingTo("0.5");   // BTC not permanently debited
        assertThat(balance(7, 3)).isEqualByComparingTo("20000"); // USDT not credited
    }

    @Test
    @DisplayName("failure leaves no residue: the identical trade retried after the fault commits exactly once")
    void retryAfterLedgerFailureCommitsCleanly() {
        doThrow(new RuntimeException(INJECTED_FAULT))
                .when(tradeMapper).insertTrade(any(Trade.class));
        TradeRequest buy = request(1, TradeType.BUY, "BTCUSDT", "0.01");

        assertThatThrownBy(() -> tradeService.executeTrade(buy))
                .hasMessage(INJECTED_FAULT);

        // Ledger heals: the spy delegates to the real mapper again.
        reset(tradeMapper);
        TradeResponse response = tradeService.executeTrade(buy);

        assertThat(response.getPrice()).isEqualByComparingTo("100"); // filled at the ask
        assertThat(tradeCount()).isEqualTo(1);
        assertThat(balance(1, 3)).isEqualByComparingTo("79");    // exactly one 1.00 debit — failed attempt took nothing
        assertThat(balance(1, 1)).isEqualByComparingTo("0.01");  // exactly one 0.01 credit
    }

    private TradeRequest request(long userId, TradeType tradeType, String pairName, String quantity) {
        TradeRequest tradeRequest = new TradeRequest();
        tradeRequest.setUserId(userId);
        tradeRequest.setTradeType(tradeType);
        tradeRequest.setPairName(pairName);
        tradeRequest.setQuantity(new BigDecimal(quantity));
        return tradeRequest;
    }

    private BigDecimal balance(long userId, long symbolId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM user_wallets WHERE user_id = ? AND symbol_id = ?",
                BigDecimal.class, userId, symbolId);
    }

    private boolean walletRowExists(long userId, long symbolId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_wallets WHERE user_id = ? AND symbol_id = ?",
                Integer.class, userId, symbolId);
        return count != null && count > 0;
    }

    private long tradeCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trades", Long.class);
        return count == null ? 0 : count;
    }
}
