package com.aquariux.technical.assessment.trade.mapper;

import com.aquariux.technical.assessment.trade.dto.internal.PairSymbolsDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CryptoPairMapper {
    
    @Select("""
            SELECT id FROM crypto_pairs WHERE pair_name = #{pairName}
            """)
    Long findIdByPairName(String pairName);

    @Select("""
            SELECT cp.id as pairId, cp.pair_name as pairName,
                   b.id as baseSymbolId, b.symbol as baseSymbol,
                   q.id as quoteSymbolId, q.symbol as quoteSymbol
            FROM crypto_pairs cp
            INNER JOIN symbols b ON cp.base_symbol_id = b.id
            INNER JOIN symbols q ON cp.quote_symbol_id = q.id
            WHERE cp.pair_name = #{pairName} AND cp.active = TRUE
            """)
    PairSymbolsDto findActiveByPairName(String pairName);
}
