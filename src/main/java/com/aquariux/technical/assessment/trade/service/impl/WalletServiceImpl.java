package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.internal.UserWalletDto;
import com.aquariux.technical.assessment.trade.dto.response.WalletBalanceResponse;
import com.aquariux.technical.assessment.trade.exception.InsufficientBalanceException;
import com.aquariux.technical.assessment.trade.mapper.UserWalletMapper;
import com.aquariux.technical.assessment.trade.service.WalletServiceInterface;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletServiceInterface {

    private final UserWalletMapper userWalletMapper;

    public List<WalletBalanceResponse> getUserWalletBalances(Long userId) {
        List<UserWalletDto> wallets = userWalletMapper.findByUserId(userId);
        
        return wallets.stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public void debit(Long userId, Long symbolId, BigDecimal amount) {
        int updatedRows = userWalletMapper.debitBalance(userId, symbolId, amount);
        if (updatedRows == 0) {
            // Either no wallet row exists (nothing ever acquired) or the balance
            // is below the requested amount. Resolve the symbol for logging only.
            UserWalletDto wallet = userWalletMapper.findByUserIdAndSymbolId(userId, symbolId);
            log.warn("Debit rejected for userId={} symbolId={} amount={} (wallet={})",
                    userId, symbolId, amount, wallet == null ? "missing" : wallet.getSymbol());
            throw new InsufficientBalanceException();
        }
    }

    @Override
    @Transactional
    public void credit(Long userId, Long symbolId, BigDecimal amount) {
        // Lazy wallet creation: the row is only created the first time the user
        // acquires this symbol. The UNIQUE(user_id, symbol_id) constraint makes
        // this a no-op for rows committed before our check, and the catch below
        // makes it a no-op for rows committed concurrently: when many first
        // acquisitions of the same symbol race, every uncommitted transaction
        // still sees no row and attempts the INSERT; only the first to commit
        // wins, the rest hit the UNIQUE constraint here — by which point the
        // row exists, which is exactly the desired outcome.
        try {
            userWalletMapper.createIfAbsent(userId, symbolId);
        } catch (DataIntegrityViolationException e) {
            log.debug("Wallet row created concurrently for userId={} symbolId={}", userId, symbolId);
        }
        int updatedRows = userWalletMapper.creditBalance(userId, symbolId, amount);
        if (updatedRows == 0) {
            // Unreachable in practice: the row is guaranteed above. Fail loudly.
            throw new IllegalStateException("Failed to credit wallet: userId=" + userId + ", symbolId=" + symbolId);
        }
    }

    private WalletBalanceResponse mapToResponse(UserWalletDto wallet) {
        WalletBalanceResponse response = new WalletBalanceResponse();
        response.setSymbol(wallet.getSymbol());
        response.setName(wallet.getName());
        response.setBalance(wallet.getBalance());
        return response;
    }
}
