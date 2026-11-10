package com.aquariux.technical.assessment.trade.mapper;

import com.aquariux.technical.assessment.trade.dto.internal.UserWalletDto;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface UserWalletMapper {
    
    @Select("""
            SELECT s.symbol, s.name, uw.balance 
            FROM symbols s 
            INNER JOIN user_wallets uw ON s.id = uw.symbol_id AND uw.user_id = #{userId} 
            ORDER BY s.symbol
            """)
    List<UserWalletDto> findByUserId(Long userId);

    @Select("""
            SELECT uw.id as id, uw.user_id as userId, uw.symbol_id as symbolId,
                   uw.balance as balance, uw.updated_at as updatedAt,
                   s.symbol as symbol, s.name as name
            FROM user_wallets uw
            INNER JOIN symbols s ON s.id = uw.symbol_id
            WHERE uw.user_id = #{userId} AND uw.symbol_id = #{symbolId}
            """)
    UserWalletDto findByUserIdAndSymbolId(Long userId, Long symbolId);

    /**
     * Creates a wallet row (balance 0) only if the user does not own the symbol yet.
     * Safe against concurrent first acquisitions: the UNIQUE(user_id, symbol_id)
     * constraint is the guard, so the statement is a no-op when the row exists.
     */
    @Insert("""
            INSERT INTO user_wallets (user_id, symbol_id, balance, updated_at)
            SELECT #{userId}, #{symbolId}, 0, CURRENT_TIMESTAMP
            WHERE NOT EXISTS (SELECT 1 FROM user_wallets uw WHERE uw.user_id = #{userId} AND uw.symbol_id = #{symbolId})
            """)
    int createIfAbsent(Long userId, Long symbolId);

    /**
     * Guarded debit: only succeeds when the wallet holds at least {@code amount}.
     * Returning 0 rows means either no wallet row (nothing held) or balance below
     * the requested amount. A single atomic statement avoids the lost-update race
     * of read-then-write balance checks under READ COMMITTED.
     */
    @Update("""
            UPDATE user_wallets
            SET balance = balance - #{amount}, updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND symbol_id = #{symbolId} AND balance >= #{amount}
            """)
    int debitBalance(Long userId, Long symbolId, BigDecimal amount);

    @Update("""
            UPDATE user_wallets
            SET balance = balance + #{amount}, updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND symbol_id = #{symbolId}
            """)
    int creditBalance(Long userId, Long symbolId, BigDecimal amount);
}
