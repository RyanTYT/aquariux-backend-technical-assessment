package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.cache.LatestPriceCache;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.exception.InsufficientBalanceException;
import com.aquariux.technical.assessment.trade.scheduler.PriceUpdateScheduler;
import com.aquariux.technical.assessment.trade.service.TradeServiceInterface;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end concurrency verification of the trade execution engine against
 * the real H2 database: many threads execute trades simultaneously and the
 * final wallet states are asserted exactly. This proves, deterministically:
 *
 * <ul>
 *   <li>the guarded debit can never over-draw a wallet (never more successful
 *       debits than the balance can afford), and</li>
 *   <li>concurrent first acquisitions of a symbol race the lazy wallet
 *       creation safely: exactly one wallet row ever exists.</li>
 * </ul>
 *
 * Prices are seeded directly into crypto_prices (BTCUSDT bid 90/ask 100,
 * ETHUSDT bid 50/ask 60) so every executed price is deterministic: BUY at 100
 * (ask), SELL at 50 (bid). The price scheduler is mocked away to keep the test
 * hermetic.
 *
 * <p>Deliberate stress settings: the H2 default 1s lock timeout is kept (no
 * override) as a tripwire — any lock timeout lands in "unexpected" and fails
 * the test, signalling the engine is too slow under contention. The Hikari
 * pool (maximum-pool-size=100 in application.yml) matches the 100 concurrent
 * trades scenario so all virtual threads contend on the database at once
 * instead of queueing on the pool.
 */
@Slf4j
@SpringBootTest
class TradeExecutionConcurrencyTest {

    @Autowired
    private TradeServiceInterface tradeService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private PriceUpdateScheduler priceUpdateScheduler;
    @Autowired
    private LatestPriceCache latestPriceCache;

    /** userId -> (symbolId -> seeded balance), mirroring V3 seed data. */
    private static final Map<Long, Map<Long, String>> SEED_WALLETS = Map.of(
            1L, Map.of(3L, "10000"),
            2L, Map.of(3L, "25000"),
            3L, Map.of(3L, "50000"),
            4L, Map.of(3L, "15000", 2L, "5"),
            5L, Map.of(3L, "30000", 2L, "10"),
            6L, Map.of(3L, "75000", 2L, "25"),
            7L, Map.of(3L, "20000", 1L, "0.5"),
            8L, Map.of(3L, "45000", 1L, "1.0"),
            9L, Map.of(3L, "100000", 2L, "15", 1L, "2.25"),
            10L, Map.of(3L, "80000", 2L, "20", 1L, "1.75"));

    @BeforeEach
    void resetSeedData() {
        // Restore the exact V3 seed state so every test starts deterministic.
        jdbcTemplate.update("DELETE FROM trades");
        latestPriceCache.clear(); // scheduler-mocked away: drop stale cached prices from the previous test
        jdbcTemplate.update("DELETE FROM crypto_prices");
        jdbcTemplate.update("DELETE FROM user_wallets");

        String seedRows = SEED_WALLETS.entrySet().stream()
                .flatMap(user -> user.getValue().entrySet().stream()
                        .map(symbol -> "(%d, %d, %s, CURRENT_TIMESTAMP)".formatted(user.getKey(), symbol.getKey(), symbol.getValue())))
                .reduce((a, b) -> a + ",\n" + b)
                .orElseThrow();
        jdbcTemplate.update("INSERT INTO user_wallets (user_id, symbol_id, balance, updated_at) VALUES " + seedRows);

        // BTCUSDT: bid 90 / ask 100; ETHUSDT: bid 50 / ask 60.
        jdbcTemplate.update("""
                INSERT INTO crypto_prices (crypto_pair_id, bid_price, ask_price, bid_source, ask_source) VALUES
                (1, 90, 100, 'TEST', 'TEST'),
                (2, 50, 60, 'TEST', 'TEST')
                """);
        // Pre-warm the cache so the 100-thread burst never races the
        // hydrate-on-miss path inside the first trades' transactions.
        latestPriceCache.getLatestForPair(1L);
        latestPriceCache.getLatestForPair(2L);
    }

    @Test
    @DisplayName("100 same-user BUYs against an 80.00 USDT balance: exactly 80 fill, 20 reject; no wallet over-drawn")
    void concurrentBuysFromSameUserDrainBalanceExactlyOncePerUnit() throws Exception {
        // User 1 gets exactly 80 USDT: 100 BUY x 0.01 BTC @ ask 100 = 1.00 each.
        jdbcTemplate.update("UPDATE user_wallets SET balance = 80 WHERE user_id = 1 AND symbol_id = 3");
        List<TradeRequest> requests = IntStream.range(0, 100)
                .mapToObj(i -> request(1, TradeType.BUY, "BTCUSDT", "0.01"))
                .toList();

        ExecutionResult result = executeConcurrently(requests);

        assertThat(result.unexpected()).isEmpty();
        assertThat(result.successes()).hasSize(80);
        assertThat(result.insufficient()).isEqualTo(20);

        assertThat(tradeCount()).isEqualTo(80);
        assertAllTradePrices("BTCUSDT", TradeType.BUY, "100"); // filled at the ask
        assertThat(balance(1, 3)).isEqualByComparingTo("0");   // USDT exactly drained
        assertThat(balance(1, 1)).isEqualByComparingTo("0.8"); // BTC credited exactly once per fill
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_wallets WHERE user_id = 1 AND symbol_id = 1", Integer.class))
                .isEqualTo(1); // exactly one BTC wallet row despite 80 concurrent creations
    }

    @Test
    @DisplayName("100 same-asset SELLs across 5 users: supply (75 ETH) caps fills exactly; per-user ledger exact")
    void concurrentSellsAcrossUsersNeverSellMoreThanHeld() throws Exception {
        // 20 attempts per user x 1 ETH @ bid 50 = +50.00 USDT per fill.
        // Each user's fills = min(20 attempts, seeded ETH): 5, 10, 20, 15, 20 = 70.
        long[] sellers = {4, 5, 6, 9, 10};
        List<TradeRequest> requests = IntStream.range(0, 100)
                .mapToObj(i -> request(sellers[i % sellers.length], TradeType.SELL, "ETHUSDT", "1"))
                .toList();

        ExecutionResult result = executeConcurrently(requests);

        assertThat(result.unexpected()).isEmpty();
        assertThat(result.successes()).hasSize(70);
        assertThat(result.insufficient()).isEqualTo(30);

        assertThat(tradeCount()).isEqualTo(70);
        assertAllTradePrices("ETHUSDT", TradeType.SELL, "50"); // filled at the bid

        // Every user sells exactly their (affordable) supply: min(20, seed) fills.
        assertThat(balance(4, 2)).isEqualByComparingTo("0");
        assertThat(balance(5, 2)).isEqualByComparingTo("0");
        assertThat(balance(6, 2)).isEqualByComparingTo("5"); // only 20 attempts, seed 25
        assertThat(balance(9, 2)).isEqualByComparingTo("0");
        assertThat(balance(10, 2)).isEqualByComparingTo("0");

        // USDT proceeds = seed + 50 per fill: 15250 / 30500 / 76000 / 100750 / 81000.
        assertThat(balance(4, 3)).isEqualByComparingTo("15250");
        assertThat(balance(5, 3)).isEqualByComparingTo("30500");
        assertThat(balance(6, 3)).isEqualByComparingTo("76000");
        assertThat(balance(9, 3)).isEqualByComparingTo("100750");
        assertThat(balance(10, 3)).isEqualByComparingTo("81000");
    }

    @Test
    @DisplayName("100 trades across assets and users (50 BUY + 50 SELL): per-user ledgers exact for both sides")
    void concurrentTradesAcrossAssetsAndUsers() throws Exception {
        // 50 BUY BTCUSDT x 0.005 @ ask 100 = 0.50 each, 5 attempts per user -> all succeed.
        // 50 SELL ETHUSDT x 2 @ bid 50 = +100 each, 10 attempts per user with seeds
        // 5/10/25/15/20 -> fills = min(10, seed/2) = 2/5/10/7/10 = 34.
        long[] ethSellers = {4, 5, 6, 9, 10};
        List<TradeRequest> requests = new ArrayList<>();
        IntStream.range(0, 50).forEach(i -> requests.add(request(i % 10 + 1, TradeType.BUY, "BTCUSDT", "0.005")));
        IntStream.range(0, 50).forEach(i -> requests.add(request(ethSellers[i % ethSellers.length], TradeType.SELL, "ETHUSDT", "2")));

        ExecutionResult result = executeConcurrently(requests);

        assertThat(result.unexpected()).isEmpty();
        assertThat(result.successes()).hasSize(84);
        assertThat(result.insufficient()).isEqualTo(16);
        assertThat(tradeCount()).isEqualTo(84);

        // BUY rows price the ask (100), SELL rows the bid (50).
        assertAllTradePrices("BTCUSDT", TradeType.BUY, "100");
        assertAllTradePrices("ETHUSDT", TradeType.SELL, "50");

        // USDT: every user pays 5 x 0.50 for BUYs; sellers earn 100 per fill.
        assertThat(balance(1, 3)).isEqualByComparingTo("9997.5");
        assertThat(balance(2, 3)).isEqualByComparingTo("24997.5");
        assertThat(balance(3, 3)).isEqualByComparingTo("49997.5");
        assertThat(balance(4, 3)).isEqualByComparingTo("15197.5"); // 15000 + 2x100 - 2.5
        assertThat(balance(5, 3)).isEqualByComparingTo("30497.5"); // 30000 + 5x100 - 2.5
        assertThat(balance(6, 3)).isEqualByComparingTo("75997.5"); // 75000 + 10x100 - 2.5
        assertThat(balance(7, 3)).isEqualByComparingTo("19997.5");
        assertThat(balance(8, 3)).isEqualByComparingTo("44997.5");
        assertThat(balance(9, 3)).isEqualByComparingTo("100697.5"); // 100000 + 7x100 - 2.5
        assertThat(balance(10, 3)).isEqualByComparingTo("80997.5"); // 80000 + 10x100 - 2.5

        // BTC: first acquisitions for users 1..6 (one row each despite races).
        // Seeded holders add +0.025 on top of their V3 balances.
        Map<Long, String> expectedBtc = Map.of(
                1L, "0.025", 2L, "0.025", 3L, "0.025",
                4L, "0.025", 5L, "0.025", 6L, "0.025",
                7L, "0.525",   // 0.5 seed + 5 x 0.005
                8L, "1.025",   // 1.0 seed + 5 x 0.005
                9L, "2.275",   // 2.25 seed + 5 x 0.005
                10L, "1.775"); // 1.75 seed + 5 x 0.005
        expectedBtc.forEach((userId, expected) ->
                assertThat(balance(userId, 1)).isEqualByComparingTo(expected).as("BTC for user %d", userId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_wallets WHERE symbol_id = 1 AND user_id BETWEEN 1 AND 6", Integer.class))
                .isEqualTo(6);

        // ETH residuals: 5-2x2 / 10-2x5 / 25-2x10 / 15-2x7 / 20-2x10.
        assertThat(balance(4, 2)).isEqualByComparingTo("1");
        assertThat(balance(5, 2)).isEqualByComparingTo("0");
        assertThat(balance(6, 2)).isEqualByComparingTo("5");
        assertThat(balance(9, 2)).isEqualByComparingTo("1");
        assertThat(balance(10, 2)).isEqualByComparingTo("0");
    }

    /**
     * Fully funded stress scenario in the worst case: every wallet holds far
     * more than any order needs, so rejections are impossible regardless of
     * arrival order. Proves that under 100-way contention every single trade
     * commits (nothing silently dropped), the per-user ledgers are exact, and
     * per-trade latency stays in a sane bound.
     */
    @Test
    @DisplayName("100 funded mixed BUY/SELLs: 100% fill, exact ledgers, latency bounded")
    void fullyFundedMixedTradesAllCommitWithBoundedLatency() throws Exception {
        // Generously pre-fund: 1 BTC and 1,000,000 USDT per user. Any of the
        // 100 orders is affordable in any order -> zero rejections possible.
        jdbcTemplate.update("UPDATE user_wallets SET balance = 1000000 WHERE symbol_id = 3");
        jdbcTemplate.update("UPDATE user_wallets SET balance = 1 WHERE symbol_id = 1");
        jdbcTemplate.update("""
                INSERT INTO user_wallets (user_id, symbol_id, balance, updated_at) VALUES
                (1, 1, 1.0, CURRENT_TIMESTAMP), (2, 1, 1.0, CURRENT_TIMESTAMP),
                (3, 1, 1.0, CURRENT_TIMESTAMP), (4, 1, 1.0, CURRENT_TIMESTAMP),
                (5, 1, 1.0, CURRENT_TIMESTAMP), (6, 1, 1.0, CURRENT_TIMESTAMP)
                """);

        // 100 trades: 50 BUYs and 50 SELLs, users cycling 1..10 in each half
        // (decoupled cycles — a single i%10/i%2 pairing is degenerate because
        // the parity of i%10 is fixed per user).
        // BUY 0.01 BTC @ ask 100 = 1.00 out; SELL 0.01 BTC @ bid 90 = 0.90 in.
        // Per user: 5 buys and 5 sells -> USDT net -0.50, BTC net 0.00.
        List<TradeRequest> requests = new ArrayList<>();
        IntStream.range(0, 50).forEach(i -> requests.add(request(i % 10 + 1, TradeType.BUY, "BTCUSDT", "0.01")));
        IntStream.range(0, 50).forEach(i -> requests.add(request(i % 10 + 1, TradeType.SELL, "BTCUSDT", "0.01")));

        ExecutionResult result = executeConcurrently(requests);

        assertThat(result.unexpected()).isEmpty(); // includes any H2 lock timeout: speed tripwire
        assertThat(result.successes()).hasSize(100);
        assertThat(result.insufficient()).isZero();

        assertThat(tradeCount()).isEqualTo(100);
        assertAllTradePrices("BTCUSDT", TradeType.BUY, "100");  // ask side
        assertAllTradePrices("BTCUSDT", TradeType.SELL, "90");  // bid side

        for (long userId = 1; userId <= 10; userId++) {
            assertThat(balance(userId, 3)).isEqualByComparingTo("999999.5").as("USDT for user %d", userId);
            assertThat(balance(userId, 1)).isEqualByComparingTo("1").as("BTC for user %d", userId);
        }

        // Latency report: loose regression bound only (not an SLA) — the real
        // speed tripwire is the 1s H2 lock timeout above. Percentiles help
        // tuning work: avg alone hides the tail (contention outliers).
        List<Long> latenciesMillis = result.latenciesNanos().stream()
                .mapToLong(nanos -> nanos / 1_000_000)
                .sorted()
                .boxed()
                .toList();
        double avgMillis = latenciesMillis.stream().mapToLong(Long::longValue).average().orElse(0);
        long p50Millis = latenciesMillis.get((latenciesMillis.size() - 1) / 2);
        int p99Index = Math.max(0, (int) Math.ceil(latenciesMillis.size() * 0.99) - 1);
        long p99Millis = latenciesMillis.get(p99Index);
        long maxMillis = latenciesMillis.get(latenciesMillis.size() - 1);
        log.info("Concurrency latency ({} trades): avg {} ms, p50 {} ms, p99 {} ms, max {} ms",
                requests.size(), avgMillis, p50Millis, p99Millis, maxMillis);
        assertThat(avgMillis).as("average per-trade latency under 100-way contention").isLessThan(100);
    }

    private TradeRequest request(long userId, TradeType tradeType, String pairName, String quantity) {
        TradeRequest tradeRequest = new TradeRequest();
        tradeRequest.setUserId(userId);
        tradeRequest.setTradeType(tradeType);
        tradeRequest.setPairName(pairName);
        tradeRequest.setQuantity(new BigDecimal(quantity));
        return tradeRequest;
    }

    private record ExecutionResult(List<TradeResponse> successes, int insufficient, List<Exception> unexpected,
                                   List<Long> latenciesNanos) {
    }

    private record Attempt(Object outcome, long latencyNanos) {
    }

    /**
     * Executes all trades at maximum contention: every worker parks on a common
     * latch, then fires on a virtual thread so the engine is hit all at once.
     * Per-attempt latency is captured for the throughput scenario. The generous
     * ceiling below only guards the harness against a stalled worker; engine
     * speed itself is policed by H2's default 1s lock timeout (a lock timeout
     * surfaces as an unexpected outcome and fails the test).
     */
    private ExecutionResult executeConcurrently(List<TradeRequest> requests) throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>();
        for (TradeRequest tradeRequest : requests) {
            futures.add(executor.submit(() -> {
                start.await();
                long startedAt = System.nanoTime();
                Object outcome;
                try {
                    outcome = tradeService.executeTrade(tradeRequest);
                } catch (Exception e) {
                    outcome = e;
                }
                return new Attempt(outcome, System.nanoTime() - startedAt);
            }));
        }
        start.countDown();

        List<TradeResponse> successes = new ArrayList<>();
        int insufficient = 0;
        List<Exception> unexpected = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        try {
            for (Future<Attempt> future : futures) {
                Attempt attempt = future.get(120, TimeUnit.SECONDS);
                latencies.add(attempt.latencyNanos());
                if (attempt.outcome() instanceof TradeResponse response) {
                    successes.add(response);
                } else if (attempt.outcome() instanceof InsufficientBalanceException) {
                    insufficient++;
                } else if (attempt.outcome() instanceof Exception exception) {
                    unexpected.add(exception);
                }
            }
        } finally {
            executor.shutdown();
        }
        return new ExecutionResult(successes, insufficient, unexpected, latencies);
    }

    private BigDecimal balance(long userId, long symbolId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM user_wallets WHERE user_id = ? AND symbol_id = ?",
                BigDecimal.class, userId, symbolId);
    }

    private long tradeCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trades", Long.class);
        return count == null ? 0 : count;
    }

    private void assertAllTradePrices(String pairName, TradeType tradeType, String expectedPrice) {
        List<BigDecimal> prices = jdbcTemplate.queryForList("""
                SELECT t.price FROM trades t
                INNER JOIN crypto_pairs cp ON cp.id = t.crypto_pair_id
                WHERE cp.pair_name = ? AND t.trade_type = ?
                """, BigDecimal.class, pairName, tradeType.name());
        assertThat(prices).isNotEmpty();
        assertThat(prices).allSatisfy(price -> assertThat(price).isEqualByComparingTo(expectedPrice));
    }
}
