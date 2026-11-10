package com.aquariux.technical.assessment.trade.mapper;

import com.aquariux.technical.assessment.trade.entity.Trade;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TradeMapper {

    @Insert("""
            INSERT INTO trades (user_id, crypto_pair_id, trade_type, quantity, price, total_amount, trade_time)
            VALUES (#{userId}, #{cryptoPairId}, #{tradeType}, #{quantity}, #{price}, #{totalAmount}, #{tradeTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTrade(Trade trade);
}
