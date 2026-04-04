package com.oreki.cas_injector.rebalancing.service;

import org.springframework.stereotype.Service;
import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PositionSizingService {
    public double calculateAdjustedBuySize(double baseAmount, MarketMetrics metrics) {
        double scoreMult = Math.max(0.2, metrics.convictionScore() / 100.0);
        double riskPenalty = (metrics.maxDrawdown() < -25.0) ? 0.6 : 1.0;
        return baseAmount * scoreMult * riskPenalty;
    }
}