package com.oreki.cas_injector.core.utils;

public enum FundStatus {
    ACTIVE,
    REDEEMED,
    DROPPED,
    EXIT,
    ACCUMULATOR,
    REBALANCER,
    WATCH,
    NEW_ENTRY,
    CORE,
    SATELLITE;

    public static FundStatus fromString(String val) {
        if (val == null) return ACTIVE;
        try {
            return FundStatus.valueOf(val.toUpperCase());
        } catch (Exception e) {
            return ACTIVE;
        }
    }
}
