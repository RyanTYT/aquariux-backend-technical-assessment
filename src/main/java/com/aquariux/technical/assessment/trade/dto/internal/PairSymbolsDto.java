package com.aquariux.technical.assessment.trade.dto.internal;

import lombok.Data;

/**
 * Projection of a crypto pair together with its base and quote symbols.
 * Used as the single lookup for resolving a pair at trade execution time.
 */
@Data
public class PairSymbolsDto {
    private Long pairId;
    private String pairName;
    private Long baseSymbolId;
    private String baseSymbol;
    private Long quoteSymbolId;
    private String quoteSymbol;
}
