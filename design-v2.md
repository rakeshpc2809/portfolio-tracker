DESIGN-v2.md: Rebalancing Strategy Revamp
Mean Reversion · Hurst Safety Gate · Volatility Harvesting · Noob-Friendly UI

Target Microservices: cas-injector (Spring Boot), portfolio-dashboard (React/TS)
Data Context: 5-Year Daily Historical NAVs
1. Core Mathematical Logic

The system will shift from momentum-based scores to Statistical Mean Reversion.
1.1 The Rolling Z-Score (The "Rubber Band")

Measures how far today's price has deviated from its 252-day (1-year) rolling average.
Z=σ252​ln(NAVtoday​)−μ252​​

    Buy Signal: Z≤−2.0 (Stretched thin, rare discount).

    Sell Signal: Z≥+2.0 (Overheated, time to harvest).

1.2 The Hurst Exponent (The "Falling Knife" Guard)

Distinguishes between a healthy dip and a structural crash.

    H<0.45 (Mean Reverting): The "Rubber Band" is valid. Buy the dip.

    H>0.55 (Trending): Price is "sliding." If moving down, don't buy yet. If moving up, "Ride the Wave" and don't sell winners early.

1.3 Volatility Harvesting (The "Free Money")

Quantifies the return drag (2σ2) caused by variance. Rebalancing "harvests" this drag.

    UI Narrative: "Capturing ₹X,XXX in extra growth created by market chaos."

2. Backend Implementation Strategy (cas-injector)
2.1 Dependencies

    Apache Commons Math 3: For calculateHurst() and calculateZScore() using log-returns.

    PostgreSQL: Use Window Functions for rolling stats to avoid OOM (Out of Memory) issues with 5-year datasets.

2.2 Nightly Engine Flow

    Step A (SQL): Compute 252-day Rolling Mean and StdDev via Postgres and update fund_conviction_metrics.

    Step B (Java): Fetch log-returns for funds and compute the Hurst Exponent using Rescaled Range (R/S) analysis.

    Step C (Reasoning): Generate the ReasoningMetadata DTO. This converts technical stats into "Noob-Friendly" strings.

3. Frontend Implementation (portfolio-dashboard)
3.1 Visual Metaphor Specification

The RecommendationDetailCard must use framer-motion to render one of these visuals:
Metaphor	Trigger Logic	Visual Element
Rubber Band	action: BUY + H<0.45	Stretched SVG path that "bounces."
Wave Rider	action: HOLD + H>0.55 + Overweight	Animated surfboard on a sine wave.
Thermometer	action: SELL + Z>2.0	Red-fill gauge rising to the Z-level.
Harvest	action: SELL + Drift > 2.5%	🌾 icon showing ₹ amount "harvested."
3.2 Signal vs. Noise Table

Display a "Noob Translation" row for every technical metric:

    Z-Score: "Statistically Cheap" (Green) or "Overheated" (Red).

    Hurst: "Bouncing Back" or "Trending Up/Down."

    Vol Tax: "Bonus Capture Potential."

4. Integration Requirements (The "Gemini CLI" Prompt)

    "Use the DESIGN-v2.md to refactor the RebalanceEngine.java.

        Ensure HurstExponentService uses org.apache.commons.math3.

        The TacticalSignal DTO must now carry a ReasoningMetadata object.

        Add a 'Sanity Bound': If Z<−4.0, trigger a CRITICAL_REVIEW instead of a BUY.

        In React, build the RecommendationDetailCard.tsx using framer-motion for physics-based animations."

5. Migration & Rollout Plan

    Phase 1: SQL migration to add hurst_exponent and rolling_z_score_252 columns to fund_conviction_metrics.

    Phase 2: Deploy the Nightly Engine to populate 5 years of historical calculations.

    Phase 3: Deploy the UI to replace raw signal rows with the "Why Cards."
