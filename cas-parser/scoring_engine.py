import math
from pydantic import BaseModel

class ScoringRequest(BaseModel):
    amfi_code: str
    personal_cagr: float
    max_cagr_found: float
    tax_pct_of_value: float
    category: str
    phil_status: str
    
    # Market Metrics
    sortino_ratio: float
    rolling_z_score_252: float
    max_drawdown: float
    ou_valid: bool
    ou_half_life: float
    hmm_bear_prob: float
    expense_ratio: float
    aum_cr: float
    nav_percentile_1yr: float

class ScoringResponse(BaseModel):
    amfi_code: str
    yield_score: float
    risk_score: float
    value_score: float
    pain_recovery_score: float
    regime_score: float
    friction_score: float
    expense_score: float
    final_conviction_score: float

# Revised 7-Factor Institutional Weights
WEIGHT_YIELD = 0.18
WEIGHT_RISK = 0.20
WEIGHT_VALUE = 0.20
WEIGHT_PAIN_RECOVERY = 0.15
WEIGHT_REGIME = 0.12
WEIGHT_FRICTION = 0.10
WEIGHT_EXPENSE = 0.05

def compute_conviction_score(req: ScoringRequest) -> ScoringResponse:
    # 1. YIELD SCORE
    yield_score = 0.0
    if req.personal_cagr > 0 and req.max_cagr_found > 0:
        yield_score = min(100.0, (req.personal_cagr / req.max_cagr_found) * 100.0)
        
    # 2. RISK SCORE (Continuous Sortino)
    risk_score = max(0.0, min(100.0, 50.0 + (req.sortino_ratio * 25.0)))
    
    # 3. VALUE SCORE (Z-Score cheapness)
    value_score = max(5.0, min(95.0, 50.0 - (req.rolling_z_score_252 * 22.5)))
    
    # 4. PAIN + RECOVERY (MDD blended with OU Half-life)
    mdd = abs(req.max_drawdown)
    pain_score = max(0.0, 100.0 - (mdd * 1.5))
    
    recovery_score = pain_score
    if req.ou_valid:
        recovery_score = max(0.0, min(100.0, 100.0 * math.exp(-req.ou_half_life / 30.0)))
        
    pain_recovery_score = (pain_score * 0.6) + (recovery_score * 0.4)
    
    # 5. REGIME SCORE (HMM Bear Prob)
    regime_score = max(0.0, 100.0 - (req.hmm_bear_prob * 100.0))
    
    # 6. FRICTION SCORE (Tax Efficiency, 20% ceiling -> multiplier 5.0)
    friction_score = max(0.0, 100.0 - (req.tax_pct_of_value * 5.0))
    
    # 7. EXPENSE + AUM BANDS
    expense_drag_score = max(0.0, 100.0 - (req.expense_ratio * 50.0))
    
    aum_score = 100.0
    if req.aum_cr < 100.0:
        aum_score = (req.aum_cr / 100.0) * 50.0
    elif req.aum_cr > 50000.0:
        aum_score = max(50.0, 100.0 - (req.aum_cr - 50000.0) / 5000.0)
        
    combined_exp_aum = (expense_drag_score * 0.7) + (aum_score * 0.3)
    
    # FINAL CALCULATION (Philosophy Driven)
    phil_status = req.phil_status.upper()
    category = req.category.upper()
    
    final_score = 0.0
    if phil_status == "REBALANCER":
        final_score = 65.0
    elif phil_status == "ACCUMULATOR":
        nav_range_score = (1.0 - req.nav_percentile_1yr) * 100.0
        final_score = (nav_range_score * 0.50) + (pain_score * 0.30) + (expense_drag_score * 0.20)
    elif "DEBT" in category or "LIQUID" in category:
        final_score = (risk_score * 0.40) + (friction_score * 0.35) + (expense_drag_score * 0.25)
    else:
        final_score = (
            (yield_score * WEIGHT_YIELD) +
            (risk_score * WEIGHT_RISK) +
            (value_score * WEIGHT_VALUE) +
            (pain_recovery_score * WEIGHT_PAIN_RECOVERY) +
            (regime_score * WEIGHT_REGIME) +
            (friction_score * WEIGHT_FRICTION) +
            (combined_exp_aum * WEIGHT_EXPENSE)
        )
    
    # DAMPENER: Structural penalty during high bear probability
    # Scaled dampening factor (1.0 to 0.5) to prevent aggressive buying in collapses
    dampening_factor = 1.0 - (req.hmm_bear_prob * 0.5)
    final_score *= dampening_factor
        
    return ScoringResponse(
        amfi_code=req.amfi_code,
        yield_score=yield_score,
        risk_score=risk_score,
        value_score=value_score,
        pain_recovery_score=pain_recovery_score,
        regime_score=regime_score,
        friction_score=friction_score,
        expense_score=combined_exp_aum,
        final_conviction_score=final_score
    )
