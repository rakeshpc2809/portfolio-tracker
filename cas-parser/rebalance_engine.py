from abc import ABC, abstractmethod
from typing import List, Dict, Optional
from pydantic import BaseModel
import math

class MarketMetrics(BaseModel):
    amfi_code: str
    conviction_score: float = 0.0
    rolling_z_score_252: float = 0.0
    hurst_exponent: float = 0.5
    hurst_regime: str = "RANDOM_WALK"
    hmm_state: str = "STRESSED_NEUTRAL"
    hmm_transition_bear_prob: float = 0.0
    ou_valid: bool = False
    ou_half_life: float = 0.0
    volatility_tax: float = 0.0
    historical_rarity_pct: float = 50.0

class StrategyTarget(BaseModel):
    isin: str
    scheme_name: str
    target_portfolio_pct: float
    sip_pct: float
    status: str
    category: str

class AggregatedHolding(BaseModel):
    isin: str
    scheme_name: str
    current_value: float
    ltcg_value: float
    ltcg_amount: float
    stcg_value: float
    stcg_amount: float
    days_to_next_ltcg: int
    nav: float = 0.0

class RebalanceRequest(BaseModel):
    pan: str
    total_portfolio_value: float
    fy_ltcg_already_realized: float
    tail_risk_level: str
    holdings: List[AggregatedHolding]
    targets: List[StrategyTarget]
    metrics: Dict[str, MarketMetrics]
    amfi_map: Dict[str, str]

class TacticalSignal(BaseModel):
    scheme_name: str
    amfi_code: str
    action: str  
    amount: float
    planned_percentage: float
    actual_percentage: float
    justifications: List[str]
    fund_status: str

class RebalanceContext:
    def __init__(self, holding: AggregatedHolding, target: StrategyTarget, metrics: MarketMetrics, req: RebalanceRequest):
        self.holding = holding
        self.target = target
        self.metrics = metrics
        self.req = req
        self.actual_pct = (holding.current_value / req.total_portfolio_value * 100.0) if req.total_portfolio_value > 0 else 0.0
        self.target_pct = target.target_portfolio_pct
        self.drift = self.actual_pct - self.target_pct
        
        self.status = target.status
        if self.target_pct == 0.0:
            self.status = "DROPPED"
        elif self.target_pct > 0.0 and self.actual_pct == 0.0:
            self.status = "NEW_ENTRY"
            
        if self.target.sip_pct > 0.0 and self.status == "DROPPED":
            self.status = "ACTIVE"

    def create_signal(self, action: str, amount: float, justifications: List[str]) -> TacticalSignal:
        return TacticalSignal(
            scheme_name=self.holding.scheme_name, amfi_code=self.metrics.amfi_code,
            action=action, amount=amount,
            planned_percentage=self.target_pct, actual_percentage=self.actual_pct,
            justifications=justifications, fund_status=self.status
        )

# --- STRATEGY PATTERN IMPLEMENTATION ---

class RebalanceStrategy(ABC):
    @abstractmethod
    def can_handle(self, ctx: RebalanceContext) -> bool:
        pass
    @abstractmethod
    def evaluate(self, ctx: RebalanceContext) -> TacticalSignal:
        pass

class ParkingVehicleStrategy(RebalanceStrategy):
    def can_handle(self, ctx: RebalanceContext) -> bool:
        cat = ctx.target.category.upper()
        return "LIQUID" in cat or "ARBITRAGE" in cat or ctx.status == "REBALANCER"
    def evaluate(self, ctx: RebalanceContext) -> TacticalSignal:
        return ctx.create_signal("HOLD", 0.0, ["Rebalancer: Liquidity parking vehicle. No tactical signals."])

class BetaMitigationStrategy(RebalanceStrategy):
    def can_handle(self, ctx: RebalanceContext) -> bool:
        return (ctx.status == "DROPPED" or ctx.status == "EXIT") and ctx.metrics.hmm_state == "VOLATILE_BEAR"
    def evaluate(self, ctx: RebalanceContext) -> TacticalSignal:
        return ctx.create_signal("EXIT", ctx.holding.current_value, ["Beta Mitigation: VOLATILE_BEAR regime. Exiting immediately to reduce drawdown exposure."])

class TaxFreeExitStrategy(RebalanceStrategy):
    def can_handle(self, ctx: RebalanceContext) -> bool:
        if not (ctx.status == "DROPPED" or ctx.status == "EXIT"): return False
        ltcg_remaining = max(0.0, 125000.0 - ctx.req.fy_ltcg_already_realized)
        return ctx.holding.ltcg_amount > 0 and ctx.holding.stcg_amount < 100 and ctx.holding.ltcg_amount <= ltcg_remaining
    def evaluate(self, ctx: RebalanceContext) -> TacticalSignal:
        return ctx.create_signal("EXIT", ctx.holding.current_value, [f"Tax-Free Exit: All unrealized gains (₹{ctx.holding.ltcg_amount:.0f}) are LTCG and fit within remaining FY exemption. Exiting NOW is tax-free."])

class StcgShieldExitStrategy(RebalanceStrategy):
    def can_handle(self, ctx: RebalanceContext) -> bool:
        if not (ctx.status == "DROPPED" or ctx.status == "EXIT"): return False
        return 0 < ctx.holding.days_to_next_ltcg <= 90 and ctx.holding.stcg_value > 1000
    def evaluate(self, ctx: RebalanceContext) -> TacticalSignal:
        if ctx.holding.ltcg_value > 1000:
            return ctx.create_signal("EXIT", ctx.holding.ltcg_value, [
                f"Partial Strategic Exit: Selling LTCG portion (₹{ctx.holding.ltcg_value:,.0f}).",
                f"STCG Shield: Holding remaining ₹{ctx.holding.stcg_value:,.0f} for {ctx.holding.days_to_next_ltcg} days to avoid 20% tax penalty."
            ])
        return ctx.create_signal("HOLD", 0.0, [f"STCG Shield: {ctx.holding.days_to_next_ltcg} days to LTCG conversion. Holding to avoid 20% STCG penalty."])

class StandardExitStrategy(RebalanceStrategy):
    def can_handle(self, ctx: RebalanceContext) -> bool:
        return ctx.status == "DROPPED" or ctx.status == "EXIT"
    def evaluate(self, ctx: RebalanceContext) -> TacticalSignal:
        return ctx.create_signal("EXIT", ctx.holding.current_value, ["Strategic Exit: Exiting dropped fund."])

class OverweightStcgShieldStrategy(RebalanceStrategy):
    def can_handle(self, ctx: RebalanceContext) -> bool:
        if ctx.status == "ACCUMULATOR": return False
        if ctx.drift <= 2.5: return False
        return 0 < ctx.holding.days_to_next_ltcg <= 90 and ctx.holding.stcg_value > 1000
    def evaluate(self, ctx: RebalanceContext) -> TacticalSignal:
        overweight_val = (ctx.drift / 100.0) * ctx.req.total_portfolio_value
        if ctx.holding.ltcg_value > 1000:
            trim_amt = min(overweight_val, ctx.holding.ltcg_value)
            return ctx.create_signal("SELL", trim_amt, [
                f"Strategic Trim: Reducing overweight position by selling LTCG portion (₹{trim_amt:,.0f}).",
                f"STCG Shield: Protecting ₹{ctx.holding.stcg_value:,.0f} from STCG tax for {ctx.holding.days_to_next_ltcg} days."
            ])
        return ctx.create_signal("HOLD", 0.0, [f"Overweight Shield: Fund is overweight by {ctx.drift:.1f}%, but holding to avoid STCG tax on recent lots."])

class TrimOverweightStrategy(RebalanceStrategy):
    def can_handle(self, ctx: RebalanceContext) -> bool:
        if ctx.status == "ACCUMULATOR": return False
        return ctx.drift > 2.5
    def evaluate(self, ctx: RebalanceContext) -> TacticalSignal:
        overweight_val = (ctx.drift / 100.0) * ctx.req.total_portfolio_value
        return ctx.create_signal("SELL", overweight_val, [
            f"Strategic Trim: Fund is overweight by {ctx.drift:.1f}%.",
            f"Target: {ctx.target_pct:.1f}% | Actual: {ctx.actual_pct:.1f}%",
            f"Suggested sell: ₹{overweight_val:,.0f} to reach target allocation."
        ])

class UnderweightBearMarketStrategy(RebalanceStrategy):
    def can_handle(self, ctx: RebalanceContext) -> bool:
        return ctx.drift < -2.5 and ctx.metrics.hmm_state == "VOLATILE_BEAR" and ctx.metrics.hmm_transition_bear_prob > 0.60
    def evaluate(self, ctx: RebalanceContext) -> TacticalSignal:
        return ctx.create_signal("WATCH", 0.0, ["Market Caution: HMM indicates high probability of bear transition. Suspending buys."])

class FillUnderweightStrategy(RebalanceStrategy):
    def can_handle(self, ctx: RebalanceContext) -> bool:
        return ctx.drift < -2.5
    def evaluate(self, ctx: RebalanceContext) -> TacticalSignal:
        deficit_val = abs(ctx.drift / 100.0) * ctx.req.total_portfolio_value
        return ctx.create_signal("BUY", deficit_val, [
            f"Strategic Realignment: Fund is underweight by {abs(ctx.drift):.1f}%.",
            f"Target: {ctx.target_pct:.1f}% | Actual: {ctx.actual_pct:.1f}%",
            f"Suggested buy: ₹{deficit_val:,.0f} to reach target allocation."
        ])

class WashSaleStrategy(RebalanceStrategy):
    def can_handle(self, ctx: RebalanceContext) -> bool:
        if abs(ctx.drift) > 2.5: return False
        ltcg_remaining = max(0.0, 125000.0 - ctx.req.fy_ltcg_already_realized)
        return 5000 < ctx.holding.ltcg_amount <= ltcg_remaining and ctx.holding.stcg_amount < 500
    def evaluate(self, ctx: RebalanceContext) -> TacticalSignal:
        return ctx.create_signal("WASH_SALE", ctx.holding.current_value, [f"Wash Sale Opportunity: Harvest ₹{ctx.holding.ltcg_amount:.0f} in tax-free LTCG."])

class MeanReversionBuyStrategy(RebalanceStrategy):
    def can_handle(self, ctx: RebalanceContext) -> bool:
        return abs(ctx.drift) <= 2.5 and ctx.metrics.hurst_exponent < 0.45 and ctx.metrics.rolling_z_score_252 < -1.5
    def evaluate(self, ctx: RebalanceContext) -> TacticalSignal:
        amount = ctx.req.total_portfolio_value * 0.01
        return ctx.create_signal("BUY", amount, ["Opportunistic Buy: Mean reversion trigger at statistical deep discount."])

class DefaultHoldStrategy(RebalanceStrategy):
    def can_handle(self, ctx: RebalanceContext) -> bool:
        return True
    def evaluate(self, ctx: RebalanceContext) -> TacticalSignal:
        return ctx.create_signal("HOLD", 0.0, ["Hold: Fund is within target allocation range."])

# --- STRATEGY ENGINE ---

class StrategyEngine:
    def __init__(self):
        # The Chain of Responsibility order is strictly maintained here.
        self.strategies = [
            ParkingVehicleStrategy(),
            BetaMitigationStrategy(),
            TaxFreeExitStrategy(),
            StcgShieldExitStrategy(),
            StandardExitStrategy(),
            OverweightStcgShieldStrategy(),
            TrimOverweightStrategy(),
            UnderweightBearMarketStrategy(),
            FillUnderweightStrategy(),
            WashSaleStrategy(),
            MeanReversionBuyStrategy(),
            DefaultHoldStrategy()
        ]

    def process(self, ctx: RebalanceContext) -> TacticalSignal:
        for strategy in self.strategies:
            if strategy.can_handle(ctx):
                return strategy.evaluate(ctx)
        return DefaultHoldStrategy().evaluate(ctx)

def compute_signals(req: RebalanceRequest) -> List[TacticalSignal]:
    signals = []
    engine = StrategyEngine()
    
    target_map = {t.isin: t for t in req.targets}
    held_isins = set(h.isin for h in req.holdings)
    
    for holding in req.holdings:
        target = target_map.get(holding.isin)
        if not target:
            target = StrategyTarget(isin=holding.isin, scheme_name=holding.scheme_name, target_portfolio_pct=0.0, sip_pct=0.0, status="DROPPED", category="OTHERS")
            
        amfi_code = req.amfi_map.get(holding.isin, "")
        metrics = req.metrics.get(amfi_code, MarketMetrics(amfi_code=amfi_code))
        
        ctx = RebalanceContext(holding, target, metrics, req)
        signals.append(engine.process(ctx))
        
    for target in req.targets:
        if target.isin not in held_isins and target.target_portfolio_pct > 0:
            holding = AggregatedHolding(isin=target.isin, scheme_name=target.scheme_name, current_value=0.0, ltcg_amount=0.0, stcg_amount=0.0, days_to_next_ltcg=0)
            amfi_code = req.amfi_map.get(target.isin, "")
            metrics = req.metrics.get(amfi_code, MarketMetrics(amfi_code=amfi_code))
            
            ctx = RebalanceContext(holding, target, metrics, req)
            signals.append(engine.process(ctx))

    return signals
