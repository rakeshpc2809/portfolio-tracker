package com.oreki.cas_injector.dashboard.dto;

import java.util.List;

import com.oreki.cas_injector.rebalancing.dto.SipLineItem;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedTacticalPayload {
    private List<SipLineItem> sipPlan;
    private List<TacticalSignal> opportunisticSignals;
    private List<TacticalSignal> exitQueue;
    private double totalExitValue;
    private int droppedFundsCount;
}
