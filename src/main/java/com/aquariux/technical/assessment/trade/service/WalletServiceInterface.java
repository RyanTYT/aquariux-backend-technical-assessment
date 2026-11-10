package com.aquariux.technical.assessment.trade.service;

import com.aquariux.technical.assessment.trade.dto.response.WalletBalanceResponse;

import java.math.BigDecimal;
import java.util.List;

public interface WalletServiceInterface {
    List<WalletBalanceResponse> getUserWalletBalances(Long userId);

    /**
     * Debits a wallet by the given amount. The debit is a single guarded SQL
     * update that also acts as the balance check, making it safe under
     * concurrent trades.
     *
     * @throws com.aquariux.technical.assessment.trade.exception.InsufficientBalanceException
     *         when the wallet is missing or its balance is below the amount
     */
    void debit(Long userId, Long symbolId, BigDecimal amount);

    /**
     * Credits a wallet by the given amount. The wallet row is created lazily
     * (balance 0) when the user acquires a symbol for the first time.
     */
    void credit(Long userId, Long symbolId, BigDecimal amount);
}
