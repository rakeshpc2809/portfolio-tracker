package com.oreki.cas_injector.core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.oreki.cas_injector.transactions.dto.TransactionDTO;

public class CommonUtilsTest {

    @Test
    public void testSolveXirr_SimpleOneYear() {
        // Invest 100 on Year 0, Get 110 on Year 1 -> 10% XIRR
        List<TransactionDTO> txs = Arrays.asList(
            new TransactionDTO(new BigDecimal("-100.00"), LocalDate.of(2020, 1, 1)),
            new TransactionDTO(new BigDecimal("110.00"), LocalDate.of(2021, 1, 1))
        );

        BigDecimal xirr = CommonUtils.SOLVE_XIRR.apply(txs);
        assertEquals(10.0, xirr.doubleValue(), 0.1);
    }

    @Test
    public void testSolveXirr_ComplexMultiple() {
        // Multi-stage investment
        // Jan 1: -10,000
        // Jul 1: -10,000
        // Next Jan 1: 22,000  (approx 13-14% XIRR)
        List<TransactionDTO> txs = Arrays.asList(
            new TransactionDTO(new BigDecimal("-10000.00"), LocalDate.of(2023, 1, 1)),
            new TransactionDTO(new BigDecimal("-10000.00"), LocalDate.of(2023, 7, 1)),
            new TransactionDTO(new BigDecimal("22000.00"), LocalDate.of(2024, 1, 1))
        );

        BigDecimal xirr = CommonUtils.SOLVE_XIRR.apply(txs);
        assertTrue(xirr.doubleValue() > 13.0 && xirr.doubleValue() < 15.0, "XIRR should be around 14%, got: " + xirr);
    }

    @Test
    public void testDetermineTaxCategory_Equity() {
        LocalDate buy = LocalDate.of(2022, 1, 1);
        LocalDate sellShort = LocalDate.of(2022, 6, 1);
        LocalDate sellLong = LocalDate.of(2023, 2, 1);

        assertEquals("EQUITY_STCG", CommonUtils.DETERMINE_TAX_CATEGORY.apply(buy, sellShort, "EQUITY"));
        assertEquals("EQUITY_LTCG", CommonUtils.DETERMINE_TAX_CATEGORY.apply(buy, sellLong, "EQUITY"));
    }

    @Test
    public void testDetermineTaxCategory_DebtNewRule() {
        // Debt bought after April 2023 is always SLAB_RATE_TAX
        LocalDate buy = LocalDate.of(2023, 4, 2);
        LocalDate sell = LocalDate.of(2026, 4, 2);

        assertEquals("SLAB_RATE_TAX", CommonUtils.DETERMINE_TAX_CATEGORY.apply(buy, sell, "DEBT"));
    }

    @Test
    public void testNormalizeName() {
        assertEquals("PARAG PARIKH FLEXI CAP", CommonUtils.NORMALIZE_NAME.apply("PARAG PARIKH FLEXI CAP FUND DIRECT PLAN GROWTH"));
        assertEquals("SBI SMALL CAP", CommonUtils.NORMALIZE_NAME.apply("SBI SMALL CAP MUTUAL FUND DIRECT-GROWTH"));
        assertEquals("HDFC MID CAP OPPORTUNITIES", CommonUtils.NORMALIZE_NAME.apply("HDFC MID CAP OPPORTUNITIES FUND - DIRECT PLAN - GROWTH"));
        assertEquals("UNKNOWN FUND", CommonUtils.NORMALIZE_NAME.apply(null));
    }
}
