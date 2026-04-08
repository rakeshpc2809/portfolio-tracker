package com.oreki.cas_injector.core.utils;

public enum SignalType {
    BUY,        // Deploy capital
    SELL,       // Rebalance overweight active fund (Gate B — after hurdle cleared)
    HOLD,       // Tax-locked or cooldown
    EXIT,       // Dropped fund — full liquidation path
    WATCH,      // Cooldown active — monitor
    HARVEST     // Tax-loss harvesting opportunity
}
