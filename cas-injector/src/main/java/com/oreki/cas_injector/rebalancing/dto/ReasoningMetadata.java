package com.oreki.cas_injector.rebalancing.dto;

/**
 * Structured explanation object attached to every TacticalSignal.
 * Designed so the frontend can render a rich "Why Card" without
 * any string-parsing or magic numbers.
 */
public record ReasoningMetadata(

    // ── Primary narrative (one sentence, plain English) ─────────────────────
    String primaryNarrative,

    // ── Technical label shown in the "Signal" column ────────────────────────
    String technicalLabel,        // e.g. "Z-Score: -2.3σ | H=0.42 (Mean Reverting)"

    // ── "Noob" translation (the big headline in the Why Card) ───────────────
    String noobHeadline,          // e.g. "This fund is on a rare discount."

    // ── UI Visual Metaphor key for the frontend to render ───────────────────
    // One of: "RUBBER_BAND" | "VOLATILITY_HARVEST" | "WAVE_RIDER" | "THERMOMETER" | "COOLING_OFF"
    String uiMetaphor,

    // ── Raw signal values ────────────────────────────────────────────────────
    double zScore,                // Rolling 252-day Z-Score (e.g., -2.3)
    double hurstExponent,         // H value (e.g., 0.42)
    double volatilityTax,         // Annual variance drain as decimal (e.g., 0.032)
    String hurstRegime,           // "MEAN_REVERTING" | "TRENDING" | "RANDOM_WALK"

    // ── Human-readable Z-Score classification ───────────────────────────────
    // One of: "STATISTICALLY_CHEAP" | "OVERHEATED" | "NEUTRAL" | "SLIGHTLY_CHEAP" | "SLIGHTLY_RICH" | "CRITICAL_REVIEW"
    String zScoreLabel,

    // ── Rarity context for the UI percentage tag ────────────────────────────
    double historicalRarityPct,   // e.g., 2.3 (meaning: "only 2.3% of days are this cheap")

    // ── Harvest context (only populated for SELL signals) ───────────────────
    double harvestAmountRupees,   // e.g., 12500.0 (the "free money" from rebalancing)
    String harvestExplanation     // e.g., "Capturing ₹12,500 in extra growth to fuel laggards."

) {
    /** Convenience factory: returns a neutral/default metadata when metrics are unavailable. */
    public static ReasoningMetadata neutral(String schemeName) {
        return new ReasoningMetadata(
            schemeName + " is within target range.",
            "Within Tolerance",
            "All good — no action needed.",
            "COOLING_OFF",
            0.0, 0.5, 0.0, "RANDOM_WALK",
            "NEUTRAL",
            50.0, 0.0, ""
        );
    }
}
