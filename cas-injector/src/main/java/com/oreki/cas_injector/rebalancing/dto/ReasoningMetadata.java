package com.oreki.cas_injector.rebalancing.dto;

/**
 * Structured explanation object attached to every TacticalSignal.
 * Designed so the frontend can render a rich "Why Card" without
 * any string-parsing or magic numbers.
 */
public record ReasoningMetadata(
    String primaryNarrative,    // e.g. "Kotak Small Cap is underweight by 4.2%"
    String technicalLabel,      // e.g. "Z-Score: -2.1σ | H=0.42"
    String noobHeadline,        // e.g. "This fund is on a rare sale. High confidence buy."
    String uiMetaphor,          // RUBBER_BAND | WAVE_RIDER | THERMOMETER | etc
    
    double zScore,
    double hurstExponent,
    double volatilityTax,
    String hurstRegime,
    
    String zScoreLabel,         // SLIGHTLY_CHEAP | OVERHEATED | etc
    double historicalRarityPct, // how rare is this dip?
    
    double harvestAmountRupees,
    String harvestExplanation,

    // ── OU fields (Task 2D) ──
    double ouHalfLifeDays,
    String ouInterpretation,

    // ── Feature Attribution (Task 5B) ──
    FeatureAttribution featureAttribution
) {
    public record FeatureAttribution(
        double zScoreContrib,
        double hurstContrib,
        double hmmContrib,
        double ouContrib,
        double taxContrib
    ) {}

    public static ReasoningMetadata neutral(String name) {
        return new ReasoningMetadata(
            name + " is within target range.",
            "Within Tolerance",
            "All good — no action needed.",
            "COOLING_OFF",
            0.0, 0.5, 0.0, "RANDOM_WALK",
            "NEUTRAL",
            50.0, 0.0, "",
            0.0, "",
            new FeatureAttribution(0.4, 0.2, 0.2, 0.1, 0.1)
        );
    }
}
