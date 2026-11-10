-- Trade execution reads the latest price per pair on every trade:
--   ORDER BY created_at DESC, id DESC LIMIT 1 (see CryptoPriceMapper.findLatestByPairName)
-- Without an index this is a scan+sort that grows with every 10s scheduler
-- tick; the composite index reduces it to an O(log n) last-entry read (the
-- id tie-break keeps the ordering deterministic).
CREATE INDEX IF NOT EXISTS idx_crypto_prices_pair_lookup
    ON crypto_prices (crypto_pair_id, created_at, id);
