package com.oreki.cas_injector.core.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.core.dto.AggregatedHolding;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SystemicRiskMonitorService {
    public enum TailRiskLevel { NORMAL, ELEVATED, CRITICAL }

    private static final double CRITICAL_CVAR_THRESHOLD = -3.50; 
    private static final double ELEVATED_CVAR_THRESHOLD = -2.50;

    public TailRiskLevel assessTailRisk(
            List<AggregatedHolding> holdings,
            Map<String, MarketMetrics> metricsMap,
            Map<String, String> nameToAmfiMap) {
        
        double totalValue = 0.0, weightedCvar = 0.0;
        for (AggregatedHolding h : holdings) {
            String amfi = nameToAmfiMap.get(h.getSchemeName());
            if (amfi != null && metricsMap.containsKey(amfi)) {
                double val = h.getCurrentValue();
                weightedCvar += (metricsMap.get(amfi).cvar5() * val);
                totalValue += val;
            }
        }
        if (totalValue == 0) return TailRiskLevel.NORMAL;
        
        double portfolioCvar = weightedCvar / totalValue;
        
        if (portfolioCvar < CRITICAL_CVAR_THRESHOLD) {
            log.error("🚨 CRITICAL Tail Risk: Portfolio CVaR at {}%.", String.format("%.2f", portfolioCvar));
            return TailRiskLevel.CRITICAL;
        } else if (portfolioCvar < ELEVATED_CVAR_THRESHOLD) {
            log.warn("⚠️ ELEVATED Tail Risk: Portfolio CVaR at {}%.", String.format("%.2f", portfolioCvar));
            return TailRiskLevel.ELEVATED;
        }
        
        return TailRiskLevel.NORMAL;
    }

    public boolean isSystemicRiskCritical(List<AggregatedHolding> holdings, Map<String, MarketMetrics> metricsMap, Map<String, String> nameToAmfiMap) {
        return assessTailRisk(holdings, metricsMap, nameToAmfiMap) == TailRiskLevel.CRITICAL;
    }
}