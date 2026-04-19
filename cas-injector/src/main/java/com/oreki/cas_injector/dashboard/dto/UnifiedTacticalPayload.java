package com.oreki.cas_injector.dashboard.dto;

import java.util.List;

import com.oreki.cas_injector.rebalancing.dto.SipLineItem;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import com.oreki.cas_injector.taxmanagement.dto.TlhOpportunity;
import com.oreki.cas_injector.rebalancing.dto.RebalancingTrade;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedTacticalPayload {
    private List<SipLineItem> sipPlan;
    private List<TacticalSignal> opportunisticSignals;
    private List<TacticalSignal> activeSellSignals;    // NEW: Gate B
    private List<TacticalSignal> exitQueue;            // Dropped funds
    private List<TacticalSignal> allSignals;           // NEW: Every signal from engine
    private List<TlhOpportunity> harvestOpportunities; // TLH integrated
    private List<RebalancingTrade> rebalancingTrades;
    private List<DroppedFundSummary> droppedFundSummaries;
    private double totalExitValue;
    private double totalHarvestValue;                  
    private int droppedFundsCount;
}
