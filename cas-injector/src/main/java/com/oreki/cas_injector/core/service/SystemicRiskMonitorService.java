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
    private static final double CRITICAL_CVAR_THRESHOLD = -3.50; 

    public boolean isSystemicRiskCritical(List<AggregatedHolding> holdings, Map<String, MarketMetrics> metricsMap, Map<String, String> nameToAmfiMap) {
        double totalValue = 0.0, weightedCvar = 0.0;
        for (AggregatedHolding h : holdings) {
            String amfi = nameToAmfiMap.get(h.getSchemeName());
            if (amfi != null && metricsMap.containsKey(amfi)) {
                double val = h.getCurrentValue();
                weightedCvar += (metricsMap.get(amfi).cvar5() * val);
                totalValue += val;
            }
        }
        if (totalValue == 0) return false;
        
        double portfolioCvar = weightedCvar / totalValue;
        boolean isCritical = portfolioCvar < CRITICAL_CVAR_THRESHOLD;
        if (isCritical) log.error("🚨 SYSTEMIC HALT: Portfolio CVaR at {}%.", String.format("%.2f", portfolioCvar));
        return isCritical;
    }
}