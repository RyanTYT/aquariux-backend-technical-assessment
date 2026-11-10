package com.aquariux.technical.assessment.trade.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SymbolMapper {

    @Select("""
            SELECT id FROM symbols WHERE symbol = #{symbol} AND active = TRUE
            """)
    Long findIdBySymbol(String symbol);
}
