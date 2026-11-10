package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.internal.PairSymbolsDto;
import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.entity.Trade;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.exception.CryptoPairNotFoundException;
import com.aquariux.technical.assessment.trade.exception.InsufficientBalanceException;
import com.aquariux.technical.assessment.trade.exception.InvalidTradeRequestException;
import com.aquariux.technical.assessment.trade.exception.PriceNotAvailableException;
import com.aquariux.technical.assessment.trade.exception.UserNotFoundException;
import com.aquariux.technical.assessment.trade.mapper.CryptoPairMapper;
import com.aquariux.technical.assessment.trade.mapper.CryptoPriceMapper;
import com.aquariux.technical.assessment.trade.mapper.TradeMapper;
import com.aquariux.technical.assessment.trade.mapper.UserMapper;
import com.aquariux.technical.assessment.trade.service.WalletServiceInterface;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeServiceImplTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private CryptoPairMapper cryptoPairMapper;
    @Mock
    private CryptoPriceMapper cryptoPriceMapper;
    @Mock
    private TradeMapper tradeMapper;
    @Mock
    private WalletServiceInterface walletService;
    @InjectMocks
    private TradeServiceImpl tradeService;

    private static final long USER_ID = 1L;
    private static final long PAIR_ID = 1L;
    private static final long BTC_SYMBOL_ID = 1L;
    private static final long USDT_SYMBOL_ID = 3L;

    private TradeRequest request;
    private PairSymbolsDto btcUsdt;
    private CryptoPrice latestPrice;

    @BeforeEach
    void setUp() {
        request = new TradeRequest();
        request.setUserId(USER_ID);
        request.setTradeType(TradeType.BUY);
        request.setPairName("BTCUSDT");
        request.setQuantity(new BigDecimal("0.01000000"));

        btcUsdt = new PairSymbolsDto();
        btcUsdt.setPairId(PAIR_ID);
        btcUsdt.setPairName("BTCUSDT");
        btcUsdt.setBaseSymbolId(BTC_SYMBOL_ID);
        btcUsdt.setBaseSymbol("BTC");
        btcUsdt.setQuoteSymbolId(USDT_SYMBOL_ID);
        btcUsdt.setQuoteSymbol("USDT");

        // Pair BTCUSDT: bid from Binance, best ask from Huobi
        latestPrice = new CryptoPrice();
        latestPrice.setCryptoPairId(PAIR_ID);
        latestPrice.setPairName("BTCUSDT");
        latestPrice.setBidPrice(new BigDecimal("44600.00000000"));
        latestPrice.setBidSource("BINANCE");
        latestPrice.setAskPrice(new BigDecimal("44650.00000000"));
        latestPrice.setAskSource("HUOBI");
    }

    private void stubSuccessfulInsert() {
        doAnswer(invocation -> {
            Trade trade = invocation.getArgument(0);
            trade.setId(99L);
            return 1;
        }).when(tradeMapper).insertTrade(any(Trade.class));
    }

    @Test
    void buyFillsAtBestAskAndMovesQuoteToBase() {
        stubSuccessfulInsert();
        when(userMapper.existsById(USER_ID)).thenReturn(true);
        when(cryptoPairMapper.findActiveByPairName("BTCUSDT")).thenReturn(btcUsdt);
        when(cryptoPriceMapper.findLatestByPairName("BTCUSDT")).thenReturn(latestPrice);

        TradeResponse response = tradeService.executeTrade(request);

        // BUY consumes the ask (lowest offer), not the bid
        verify(walletService).debit(USER_ID, USDT_SYMBOL_ID, new BigDecimal("446.50000000"));
        verify(walletService).credit(USER_ID, BTC_SYMBOL_ID, new BigDecimal("0.01000000"));

        ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeMapper).insertTrade(tradeCaptor.capture());
        Trade persisted = tradeCaptor.getValue();
        assertThat(persisted.getUserId()).isEqualTo(USER_ID);
        assertThat(persisted.getCryptoPairId()).isEqualTo(PAIR_ID);
        assertThat(persisted.getTradeType()).isEqualTo("BUY");
        assertThat(persisted.getQuantity()).isEqualByComparingTo("0.01000000");
        assertThat(persisted.getPrice()).isEqualByComparingTo("44650.00000000");
        assertThat(persisted.getTotalAmount()).isEqualByComparingTo("446.50000000");
        assertThat(persisted.getTradeTime()).isNotNull();

        assertThat(response.getTradeId()).isEqualTo(99L);
        assertThat(response.getPairName()).isEqualTo("BTCUSDT");
        assertThat(response.getTradeType()).isEqualTo(TradeType.BUY);
        assertThat(response.getQuantity()).isEqualByComparingTo("0.01000000");
        assertThat(response.getPrice()).isEqualByComparingTo("44650.00000000");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("446.50000000");
        assertThat(response.getTradeTime()).isNotNull();
    }

    @Test
    void sellFillsAtBestBidAndMovesBaseToQuote() {
        stubSuccessfulInsert();
        request.setTradeType(TradeType.SELL);
        request.setQuantity(new BigDecimal("0.50000000"));

        when(userMapper.existsById(USER_ID)).thenReturn(true);
        when(cryptoPairMapper.findActiveByPairName("BTCUSDT")).thenReturn(btcUsdt);
        when(cryptoPriceMapper.findLatestByPairName("BTCUSDT")).thenReturn(latestPrice);

        TradeResponse response = tradeService.executeTrade(request);

        // SELL delivers the base asset at the bid (highest offer to buy)
        verify(walletService).debit(USER_ID, BTC_SYMBOL_ID, new BigDecimal("0.50000000"));
        verify(walletService).credit(USER_ID, USDT_SYMBOL_ID, new BigDecimal("22300.00000000"));

        ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeMapper).insertTrade(tradeCaptor.capture());
        assertThat(tradeCaptor.getValue().getTradeType()).isEqualTo("SELL");

        assertThat(response.getTradeId()).isEqualTo(99L);
        assertThat(response.getTradeType()).isEqualTo(TradeType.SELL);
        assertThat(response.getPrice()).isEqualByComparingTo("44600.00000000");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("22300.00000000");
    }

    @Test
    void insufficientBalanceRejectsTradeWithoutSideEffects() {
        when(userMapper.existsById(USER_ID)).thenReturn(true);
        when(cryptoPairMapper.findActiveByPairName("BTCUSDT")).thenReturn(btcUsdt);
        when(cryptoPriceMapper.findLatestByPairName("BTCUSDT")).thenReturn(latestPrice);
        doThrow(new InsufficientBalanceException())
                .when(walletService).debit(eq(USER_ID), eq(USDT_SYMBOL_ID), any(BigDecimal.class));

        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(InsufficientBalanceException.class);

        // Wallet rows are mutated in ascending symbol-id order, so for a BUY the
        // base-side credit runs before the guarded debit rejects the trade. The
        // transaction boundary rolls that in-flight credit back, so nothing
        // persists; exactly these two wallet interactions happen and, crucially,
        // the trades ledger is never written.
        verify(walletService).credit(USER_ID, BTC_SYMBOL_ID, new BigDecimal("0.01000000"));
        verify(walletService).debit(USER_ID, USDT_SYMBOL_ID, new BigDecimal("446.50000000"));
        verifyNoMoreInteractions(walletService);
        verifyNoInteractions(tradeMapper);
    }

    @Test
    void unknownUserIsRejected() {
        when(userMapper.existsById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining("1");

        verifyNoInteractions(cryptoPairMapper, cryptoPriceMapper, walletService, tradeMapper);
    }

    @Test
    void unknownPairIsRejected() {
        request.setPairName("XRPUSDT");
        when(userMapper.existsById(USER_ID)).thenReturn(true);
        when(cryptoPairMapper.findActiveByPairName("XRPUSDT")).thenReturn(null);

        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(CryptoPairNotFoundException.class);

        verifyNoInteractions(cryptoPriceMapper, walletService, tradeMapper);
    }

    @Test
    void pairNameLookupIsCaseInsensitive() {
        stubSuccessfulInsert();
        request.setPairName(" btcusdt ");
        when(userMapper.existsById(USER_ID)).thenReturn(true);
        when(cryptoPairMapper.findActiveByPairName("BTCUSDT")).thenReturn(btcUsdt);
        when(cryptoPriceMapper.findLatestByPairName("BTCUSDT")).thenReturn(latestPrice);

        TradeResponse response = tradeService.executeTrade(request);

        verify(walletService).debit(eq(USER_ID), eq(USDT_SYMBOL_ID), any(BigDecimal.class));
        verify(walletService).credit(eq(USER_ID), eq(BTC_SYMBOL_ID), eq(new BigDecimal("0.01000000")));
        assertThat(response.getPairName()).isEqualTo("BTCUSDT");
    }

    @Test
    void missingPriceRejectsTrade() {
        when(userMapper.existsById(USER_ID)).thenReturn(true);
        when(cryptoPairMapper.findActiveByPairName("BTCUSDT")).thenReturn(btcUsdt);
        when(cryptoPriceMapper.findLatestByPairName("BTCUSDT")).thenReturn(null);

        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(PriceNotAvailableException.class)
                .hasMessageContaining("BTCUSDT");

        verifyNoInteractions(walletService, tradeMapper);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidRequestsAreRejected(TradeRequest invalid) {
        assertThatThrownBy(() -> tradeService.executeTrade(invalid))
                .isInstanceOf(InvalidTradeRequestException.class);

        verifyNoInteractions(userMapper, cryptoPairMapper, cryptoPriceMapper, walletService, tradeMapper);
    }

    private static Stream<TradeRequest> invalidRequests() {
        TradeRequest missingType = new TradeRequest();
        missingType.setUserId(USER_ID);

        TradeRequest missingPair = new TradeRequest();
        missingPair.setUserId(USER_ID);
        missingPair.setTradeType(TradeType.BUY);

        TradeRequest missingQuantity = new TradeRequest();
        missingQuantity.setUserId(USER_ID);
        missingQuantity.setTradeType(TradeType.BUY);
        missingQuantity.setPairName("BTCUSDT");

        TradeRequest zeroQuantity = new TradeRequest();
        zeroQuantity.setUserId(USER_ID);
        zeroQuantity.setTradeType(TradeType.BUY);
        zeroQuantity.setPairName("BTCUSDT");
        zeroQuantity.setQuantity(BigDecimal.ZERO);

        TradeRequest negativeQuantity = new TradeRequest();
        negativeQuantity.setUserId(USER_ID);
        negativeQuantity.setTradeType(TradeType.SELL);
        negativeQuantity.setPairName("BTCUSDT");
        negativeQuantity.setQuantity(new BigDecimal("-1"));

        TradeRequest missingUser = new TradeRequest();
        missingUser.setTradeType(TradeType.BUY);
        missingUser.setPairName("BTCUSDT");
        missingUser.setQuantity(BigDecimal.ONE);

        return Stream.of(null, missingType, missingPair, missingQuantity, zeroQuantity, negativeQuantity, missingUser);
    }
}
