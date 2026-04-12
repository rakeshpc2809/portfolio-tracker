package com.oreki.cas_injector.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
public class BenchmarkServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BenchmarkService benchmarkService;

    @Test
    public void testGetBenchmarkReturn_WithActualData() {
        // Mock the CTE SQL result
        when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), eq("NIFTY 50"), eq("NIFTY 50")))
            .thenReturn(15.5);

        double result = benchmarkService.getBenchmarkReturn("CORE", "LARGE CAP", null);
        assertEquals(15.5, result, 0.01);
    }

    @Test
    public void testGetBenchmarkReturn_FallbackToConstants() {
        // Mock SQL failure or no rows
        when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), anyString(), anyString()))
            .thenThrow(new RuntimeException("No data"));

        double result = benchmarkService.getBenchmarkReturn("CORE", "MID CAP", null);
        assertEquals(22.4, result, 0.01);
    }

    @Test
    public void testGetBenchmarkReturnsForAllPeriods() {
        // Mock computeReturnForPeriod for various intervals
        when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), anyString(), anyString(), any()))
            .thenReturn(5.0);

        Map<String, Double> returns = benchmarkService.getBenchmarkReturnsForAllPeriods("NIFTY 50");
        assert(returns.containsKey("1M"));
        assert(returns.containsKey("1Y"));
        assertEquals(5.0, returns.get("1Y"));
    }
}
