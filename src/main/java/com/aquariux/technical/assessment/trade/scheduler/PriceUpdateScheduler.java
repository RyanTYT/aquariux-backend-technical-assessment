package com.aquariux.technical.assessment.trade.scheduler;

import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.mapper.CryptoPairMapper;
import com.aquariux.technical.assessment.trade.mapper.CryptoPriceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class PriceUpdateScheduler {

    private final RestTemplate restTemplate;
    private final CryptoPairMapper cryptoPairMapper;
    private final CryptoPriceMapper cryptoPriceMapper;

    private static final String BINANCE_URL = "https://api.binance.com/api/v3/ticker/bookTicker";
    private static final String HUOBI_URL = "https://api.huobi.pro/market/tickers";

    @Scheduled(fixedRate = 10000) // 10 seconds
    public void updatePrices() {
        log.info("Starting price update from exchanges");
        
        try {
            // Fetch Binance prices
            List<Map<String, Object>> binancePrices = fetchBinancePrices();
            
            // Fetch Huobi prices
            Map<String, Object> huobiResponse = fetchHuobiPrices();
            
            // Process and store best prices
            processPrices(binancePrices, huobiResponse);
            
        } catch (Exception e) {
            log.error("Error updating prices: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> fetchBinancePrices() {
        try {
            return restTemplate.getForObject(BINANCE_URL, List.class);
        } catch (Exception e) {
            log.error("Error fetching Binance prices: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> fetchHuobiPrices() {
        try {
            return restTemplate.getForObject(HUOBI_URL, Map.class);
        } catch (Exception e) {
            log.error("Error fetching Huobi prices: {}", e.getMessage());
            return Map.of();
        }
    }

    private void processPrices(List<Map<String, Object>> binancePrices, Map<String, Object> huobiResponse) {
        // Process supported pairs: BTCUSDT, ETHUSDT
        String[] supportedPairs = {"BTCUSDT", "ETHUSDT"};
        
        for (String pair : supportedPairs) {
            BigDecimal bestBidPrice = BigDecimal.ZERO;
            BigDecimal bestAskPrice = BigDecimal.valueOf(Double.MAX_VALUE);
            String bestBidSource = "";
            String bestAskSource = "";
            
            // Find best prices from Binance
            for (Map<String, Object> ticker : binancePrices) {
                if (pair.equals(ticker.get("symbol"))) {
                    BigDecimal bidPrice = new BigDecimal(ticker.get("bidPrice").toString());
                    BigDecimal askPrice = new BigDecimal(ticker.get("askPrice").toString());
                    
                    if (bidPrice.compareTo(bestBidPrice) > 0) {
                        bestBidPrice = bidPrice;
                        bestBidSource = "BINANCE";
                    }
                    if (askPrice.compareTo(bestAskPrice) < 0) {
                        bestAskPrice = askPrice;
                        bestAskSource = "BINANCE";
                    }
                }
            }
            
            // Find best prices from Huobi
            if (huobiResponse.containsKey("data")) {
                List<Map<String, Object>> huobiTickers = (List<Map<String, Object>>) huobiResponse.get("data");
                String huobiSymbol = pair.toLowerCase();
                
                for (Map<String, Object> ticker : huobiTickers) {
                    if (huobiSymbol.equals(ticker.get("symbol"))) {
                        BigDecimal bidPrice = new BigDecimal(ticker.get("bid").toString());
                        BigDecimal askPrice = new BigDecimal(ticker.get("ask").toString());
                        
                        if (bidPrice.compareTo(bestBidPrice) > 0) {
                            bestBidPrice = bidPrice;
                            bestBidSource = "HUOBI";
                        }
                        if (askPrice.compareTo(bestAskPrice) < 0) {
                            bestAskPrice = askPrice;
                            bestAskSource = "HUOBI";
                        }
                    }
                }
            }
            
            // Store best price with source info
            if (bestAskPrice.compareTo(BigDecimal.valueOf(Double.MAX_VALUE)) < 0) {
                storePriceHistory(pair, bestBidPrice, bestAskPrice, bestBidSource, bestAskSource);
                log.info("Stored {} best price - Bid: {} ({}), Ask: {} ({})", 
                    pair, bestBidPrice, bestBidSource, bestAskPrice, bestAskSource);
            }
        }
    }
    
    private void storePriceHistory(String pairName, BigDecimal bidPrice, BigDecimal askPrice, String bidSource, String askSource) {
        Long cryptoPairId = getCryptoPairId(pairName);
        
        if (cryptoPairId != null) {
            CryptoPrice cryptoPrice = new CryptoPrice();
            cryptoPrice.setCryptoPairId(cryptoPairId);
            cryptoPrice.setBidPrice(bidPrice);
            cryptoPrice.setAskPrice(askPrice);
            cryptoPrice.setBidSource(bidSource);
            cryptoPrice.setAskSource(askSource);
            
            cryptoPriceMapper.insertPrice(cryptoPrice);
        } else {
            log.warn("Crypto pair not found: {}", pairName);
        }
    }
    
    private Long getCryptoPairId(String pairName) {
        // NOTE (pre-existing logic, kept intentional):
        // The original implementation resolves the pair id using a pair name that is
        // deliberately swapped (BTCUSDT <-> ETHUSDT). Since the system only supports
        // these two pairs, this looks like intentional behavior rather than a bug:
        //
        //   String swappedPair = "BTCUSDT".equals(pairName) ? "ETHUSDT" : "BTCUSDT";
        //   return cryptoPairMapper.findIdByPairName(swappedPair);
        //
        // The corrected (non-swapped) resolution is used below so that a stored price
        // row is attributed to the pair it was actually quoted for.
        return cryptoPairMapper.findIdByPairName(pairName);
    }
}