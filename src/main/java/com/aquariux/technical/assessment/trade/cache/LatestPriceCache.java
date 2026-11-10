package com.aquariux.technical.assessment.trade.cache;

import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.mapper.CryptoPriceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory read-through cache of the latest stored price per trading pair.
 *
 * <p>Prices only change on the {@code PriceUpdateScheduler} tick (every 10s),
 * so the "latest stored price" is fully deterministic up to that point.
 * Serving it from memory removes one database query from every trade.
 *
 * <p>Lifecycle: the scheduler pushes each freshly persisted price into the
 * cache ({@link #put}), which keeps an entry at most one tick behind the
 * table. The first access after a cold start hydrates from the database
 * ({@link #getLatestForPair}), covering app startup before the first tick —
 * and integration tests that seed price rows directly and run with the real
 * scheduler mocked away. {@link #clear()} exists for test reset determinism.
 */
@Component
@RequiredArgsConstructor
public class LatestPriceCache {

    private final CryptoPriceMapper cryptoPriceMapper;

    private final ConcurrentHashMap<Long, CryptoPrice> latestByPairId = new ConcurrentHashMap<>();

    /**
     * Latest stored price for a pair, or {@code null} when no price row
     * exists yet (the caller maps this to price-not-available).
     */
    public CryptoPrice getLatestForPair(Long pairId) {
        CryptoPrice cached = latestByPairId.get(pairId);
        if (cached != null) {
            return cached;
        }
        CryptoPrice hydrated = cryptoPriceMapper.findLatestByPairId(pairId);
        if (hydrated != null) {
            latestByPairId.put(pairId, hydrated);
        }
        return hydrated;
    }

    /** Called by the price scheduler right after a new price row is stored. */
    public void put(Long pairId, CryptoPrice price) {
        latestByPairId.put(pairId, price);
    }

    /** Drops all cached entries (used by tests to reset determinism). */
    public void clear() {
        latestByPairId.clear();
    }
}
