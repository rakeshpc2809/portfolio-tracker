package com.oreki.cas_injector.convictionmetrics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

class HurstExponentServiceTest {

    private HurstExponentService hurstService;

    @BeforeEach
    void setUp() {
        hurstService = new HurstExponentService(null);
    }

    @Test
    void testCalculateHurst_RandomWalk() {
        // Random walk (uncorrelated returns) -> H should be ~0.5
        double[] returns = new double[252];
        for (int i = 0; i < 252; i++) {
            returns[i] = (Math.random() - 0.5) * 0.02;
        }

        double h = hurstService.calculateHurst(returns);
        assertTrue(h > 0.3 && h < 0.7, "Hurst should be near 0.5 for random walk, got: " + h);
    }

    @Test
    void testCalculateHurst_Momentum() {
        // Positively correlated returns (Momentum/Trending) -> H should be > 0.5
        double[] returns = new double[252];
        returns[0] = 0.01;
        for (int i = 1; i < 252; i++) {
            // Strong positive autocorrelation
            returns[i] = (0.7 * returns[i-1]) + (Math.random() - 0.5) * 0.001; 
        }

        double h = hurstService.calculateHurst(returns);
        assertTrue(h > 0.5, "Hurst should be > 0.5 for momentum series, got: " + h);
    }

    @Test
    void testCalculateHurst_MeanReverting() {
        // Negatively correlated returns (Mean Reverting) -> H should be < 0.5
        double[] returns = new double[252];
        for (int i = 0; i < 252; i++) {
            // Alternate signs
            returns[i] = (i % 2 == 0 ? 0.02 : -0.02) + (Math.random() - 0.5) * 0.001;
        }

        double h = hurstService.calculateHurst(returns);
        assertTrue(h < 0.5, "Hurst should be < 0.5 for mean-reverting series, got: " + h);
    }

    @Test
    void testCalculateVolatilityTax() {
        double[] returns = new double[252];
        for (int i = 0; i < 252; i += 2) {
            returns[i] = 0.01;
            returns[i+1] = -0.01;
        }

        double vt = hurstService.calculateVolatilityTax(returns);
        assertEquals(0.0504, vt, 0.001);
    }
}
