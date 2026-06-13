package com.oreki.cas_injector.rebalancing.service;

import com.oreki.cas_injector.convictionmetrics.dto.MarketMetrics;
import com.oreki.cas_injector.core.dto.AggregatedHolding;
import com.oreki.cas_injector.core.utils.FundStatus;
import com.oreki.cas_injector.core.utils.SignalType;
import com.oreki.cas_injector.rebalancing.dto.StrategyTarget;
import com.oreki.cas_injector.rebalancing.dto.TacticalSignal;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RebalanceEngine {

    @Data
    @Builder
    public static class RebalanceRequest {
        private String pan;
        private BigDecimal totalPortfolioValue;
        private double fyLtcgAlreadyRealized;
        private String tailRiskLevel;
        private List<AggregatedHolding> holdings;
        private List<StrategyTarget> targets;
        private Map<String, MarketMetrics> metrics;
        private Map<String, String> amfiMap;
    }

    private static class RebalanceContext {
        AggregatedHolding holding;
        StrategyTarget target;
        MarketMetrics metrics;
        RebalanceRequest req;
        String amfiCode;
        double actualPct;
        double targetPct;
        double drift;
        String status;

        RebalanceContext(AggregatedHolding holding, StrategyTarget target, MarketMetrics metrics, RebalanceRequest req, String amfiCode) {
            this.holding = holding;
            this.target = target;
            this.metrics = metrics;
            this.req = req;
            this.amfiCode = amfiCode;
            this.actualPct = req.totalPortfolioValue.compareTo(BigDecimal.ZERO) > 0 ? ((holding.getCurrentValue() != null ? holding.getCurrentValue() : BigDecimal.ZERO).divide(req.totalPortfolioValue, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100.0)).doubleValue()) : 0.0;
            this.targetPct = target.targetPortfolioPct();
            this.drift = this.actualPct - this.targetPct;
            
            this.status = target.status();
            if (this.targetPct == 0.0) {
                this.status = "DROPPED";
            } else if (this.targetPct > 0.0 && this.actualPct == 0.0) {
                this.status = "NEW_ENTRY";
            }
                
            if (target.sipPct() > 0.0 && "DROPPED".equals(this.status)) {
                this.status = "ACTIVE";
            }
        }

        double val() { return holding.getCurrentValue() != null ? holding.getCurrentValue().doubleValue() : 0.0; }
        double ltcgG() { return holding.getLtcgAmount() != null ? holding.getLtcgAmount().doubleValue() : 0.0; }
        double stcgG() { return holding.getStcgAmount() != null ? holding.getStcgAmount().doubleValue() : 0.0; }
        double ltcgV() { return holding.getLtcgValue() != null ? holding.getLtcgValue().doubleValue() : 0.0; }
        double stcgV() { return holding.getStcgValue() != null ? holding.getStcgValue().doubleValue() : 0.0; }

        TacticalSignal createSignal(String action, double amount, List<String> justifications) {
            return TacticalSignal.builder()
                .schemeName(holding.getSchemeName())
                .amfiCode(amfiCode)
                .action(SignalType.valueOf(action))
                .amount(String.format(java.util.Locale.US, "%.2f", amount))
                .plannedPercentage(targetPct)
                .actualPercentage(actualPct)
                .justifications(justifications)
                .fundStatus(FundStatus.fromString(status))
                .convictionScore(metrics.convictionScore())
                .sortinoRatio(metrics.sortinoRatio())
                .maxDrawdown(metrics.maxDrawdown())
                .navPercentile1yr(metrics.navPercentile1yr())
                .navPercentile3yr(metrics.navPercentile3yr())
                .drawdownFromAth(metrics.drawdownFromAth())
                .returnZScore(metrics.returnZScore())
                .winRate(metrics.winRate())
                .cvar5(metrics.cvar5())
                .lastBuyDate(metrics.lastBuyDate())
                .yieldScore(metrics.yieldScore())
                .riskScore(metrics.riskScore())
                .valueScore(metrics.valueScore())
                .painScore(metrics.painScore())
                .regimeScore(metrics.regimeScore())
                .frictionScore(metrics.frictionScore())
                .expenseScore(metrics.expenseScore())
                .expenseRatio(metrics.expenseRatio())
                .aumCr(metrics.aumCr())
                .ouHalfLife(metrics.ouHalfLife())
                .ouValid(metrics.ouValid())
                .build();
        }
    }

    private interface RebalanceStrategy {
        boolean canHandle(RebalanceContext ctx);
        TacticalSignal evaluate(RebalanceContext ctx);
    }

    private static class ParkingVehicleStrategy implements RebalanceStrategy {
        public boolean canHandle(RebalanceContext ctx) {
            String cat = ctx.target.bucket() != null ? ctx.target.bucket().toUpperCase() : "";
            return cat.contains("LIQUID") || cat.contains("ARBITRAGE") || "REBALANCER".equals(ctx.status);
        }
        public TacticalSignal evaluate(RebalanceContext ctx) {
            return ctx.createSignal("HOLD", 0.0, List.of("Rebalancer: Liquidity parking vehicle. No tactical signals."));
        }
    }

    private static class BetaMitigationStrategy implements RebalanceStrategy {
        public boolean canHandle(RebalanceContext ctx) {
            return ("DROPPED".equals(ctx.status) || "EXIT".equals(ctx.status)) && "VOLATILE_BEAR".equals(ctx.metrics.hmmState());
        }
        public TacticalSignal evaluate(RebalanceContext ctx) {
            return ctx.createSignal("EXIT", ctx.val(), List.of("Beta Mitigation: VOLATILE_BEAR regime. Exiting immediately to reduce drawdown exposure."));
        }
    }

    private static class TaxFreeExitStrategy implements RebalanceStrategy {
        public boolean canHandle(RebalanceContext ctx) {
            if (!("DROPPED".equals(ctx.status) || "EXIT".equals(ctx.status))) return false;
            double ltcgRemaining = Math.max(0.0, 125000.0 - ctx.req.fyLtcgAlreadyRealized);
            return ctx.ltcgG() > 0 && ctx.stcgG() < 100 && ctx.ltcgG() <= ltcgRemaining;
        }
        public TacticalSignal evaluate(RebalanceContext ctx) {
            return ctx.createSignal("EXIT", ctx.val(), List.of(String.format("Tax-Free Exit: All unrealized gains (₹%.0f) are LTCG and fit within remaining FY exemption. Exiting NOW is tax-free.", ctx.ltcgG())));
        }
    }

    private static class StcgShieldExitStrategy implements RebalanceStrategy {
        public boolean canHandle(RebalanceContext ctx) {
            if (!("DROPPED".equals(ctx.status) || "EXIT".equals(ctx.status))) return false;
            return ctx.holding.getDaysToNextLtcg() > 0 && ctx.holding.getDaysToNextLtcg() <= 90 && ctx.stcgV() > 1000;
        }
        public TacticalSignal evaluate(RebalanceContext ctx) {
            if (ctx.ltcgV() > 1000) {
                return ctx.createSignal("EXIT", ctx.ltcgV(), List.of(
                    String.format("Partial Strategic Exit: Selling LTCG portion (₹%,.0f).", ctx.ltcgV()),
                    String.format("STCG Shield: Holding remaining ₹%,.0f for %d days to avoid 20%% tax penalty.", ctx.stcgV(), ctx.holding.getDaysToNextLtcg())
                ));
            }
            return ctx.createSignal("HOLD", 0.0, List.of(String.format("STCG Shield: %d days to LTCG conversion. Holding to avoid 20%% STCG penalty.", ctx.holding.getDaysToNextLtcg())));
        }
    }

    private static class StandardExitStrategy implements RebalanceStrategy {
        public boolean canHandle(RebalanceContext ctx) {
            return "DROPPED".equals(ctx.status) || "EXIT".equals(ctx.status);
        }
        public TacticalSignal evaluate(RebalanceContext ctx) {
            return ctx.createSignal("EXIT", ctx.val(), List.of("Strategic Exit: Exiting dropped fund."));
        }
    }

    private static class OverweightStcgShieldStrategy implements RebalanceStrategy {
        public boolean canHandle(RebalanceContext ctx) {
            if ("ACCUMULATOR".equals(ctx.status)) return false;
            if (ctx.drift <= 2.5) return false;
            return ctx.holding.getDaysToNextLtcg() > 0 && ctx.holding.getDaysToNextLtcg() <= 90 && ctx.stcgV() > 1000;
        }
        public TacticalSignal evaluate(RebalanceContext ctx) {
            double overweightVal = (ctx.drift / 100.0) * ctx.req.totalPortfolioValue.doubleValue();
            if (ctx.ltcgV() > 1000) {
                double trimAmt = Math.min(overweightVal, ctx.ltcgV());
                return ctx.createSignal("SELL", trimAmt, List.of(
                    String.format("Strategic Trim: Reducing overweight position by selling LTCG portion (₹%,.0f).", trimAmt),
                    String.format("STCG Shield: Protecting ₹%,.0f from STCG tax for %d days.", ctx.stcgV(), ctx.holding.getDaysToNextLtcg())
                ));
            }
            return ctx.createSignal("HOLD", 0.0, List.of(String.format("Overweight Shield: Fund is overweight by %.1f%%, but holding to avoid STCG tax on recent lots.", ctx.drift)));
        }
    }

    private static class TrimOverweightStrategy implements RebalanceStrategy {
        public boolean canHandle(RebalanceContext ctx) {
            if ("ACCUMULATOR".equals(ctx.status)) return false;
            return ctx.drift > 2.5;
        }
        public TacticalSignal evaluate(RebalanceContext ctx) {
            double overweightVal = (ctx.drift / 100.0) * ctx.req.totalPortfolioValue.doubleValue();
            return ctx.createSignal("SELL", overweightVal, List.of(
                String.format("Strategic Trim: Fund is overweight by %.1f%%.", ctx.drift),
                String.format("Target: %.1f%% | Actual: %.1f%%", ctx.targetPct, ctx.actualPct),
                String.format("Suggested sell: ₹%,.0f to reach target allocation.", overweightVal)
            ));
        }
    }

    private static class UnderweightBearMarketStrategy implements RebalanceStrategy {
        public boolean canHandle(RebalanceContext ctx) {
            return ctx.drift < -2.5 && "VOLATILE_BEAR".equals(ctx.metrics.hmmState()) && ctx.metrics.hmmTransitionBearProb() > 0.60;
        }
        public TacticalSignal evaluate(RebalanceContext ctx) {
            return ctx.createSignal("WATCH", 0.0, List.of("Market Caution: HMM indicates high probability of bear transition. Suspending buys."));
        }
    }

    private static class FillUnderweightStrategy implements RebalanceStrategy {
        public boolean canHandle(RebalanceContext ctx) {
            return ctx.drift < -2.5;
        }
        public TacticalSignal evaluate(RebalanceContext ctx) {
            double deficitVal = Math.abs(ctx.drift / 100.0) * ctx.req.totalPortfolioValue.doubleValue();
            return ctx.createSignal("BUY", deficitVal, List.of(
                String.format("Strategic Realignment: Fund is underweight by %.1f%%.", Math.abs(ctx.drift)),
                String.format("Target: %.1f%% | Actual: %.1f%%", ctx.targetPct, ctx.actualPct),
                String.format("Suggested buy: ₹%,.0f to reach target allocation.", deficitVal)
            ));
        }
    }

    private static class WashSaleStrategy implements RebalanceStrategy {
        public boolean canHandle(RebalanceContext ctx) {
            if (Math.abs(ctx.drift) > 2.5) return false;
            double ltcgRemaining = Math.max(0.0, 125000.0 - ctx.req.fyLtcgAlreadyRealized);
            return ctx.ltcgG() > 5000 && ctx.ltcgG() <= ltcgRemaining && ctx.stcgG() < 500;
        }
        public TacticalSignal evaluate(RebalanceContext ctx) {
            return ctx.createSignal("WASH_SALE", ctx.val(), List.of(String.format("Wash Sale Opportunity: Harvest ₹%.0f in tax-free LTCG.", ctx.ltcgG())));
        }
    }

    private static class MeanReversionBuyStrategy implements RebalanceStrategy {
        public boolean canHandle(RebalanceContext ctx) {
            return Math.abs(ctx.drift) <= 2.5 && ctx.metrics.hurstExponent() < 0.45 && ctx.metrics.rollingZScore252() < -1.5;
        }
        public TacticalSignal evaluate(RebalanceContext ctx) {
            double amount = ctx.req.totalPortfolioValue.doubleValue() * 0.01;
            return ctx.createSignal("BUY", amount, List.of("Opportunistic Buy: Mean reversion trigger at statistical deep discount."));
        }
    }

    private static class DefaultHoldStrategy implements RebalanceStrategy {
        public boolean canHandle(RebalanceContext ctx) {
            return true;
        }
        public TacticalSignal evaluate(RebalanceContext ctx) {
            return ctx.createSignal("HOLD", 0.0, List.of("Hold: Fund is within target allocation range."));
        }
    }

    private final List<RebalanceStrategy> strategies = List.of(
        new ParkingVehicleStrategy(),
        new BetaMitigationStrategy(),
        new TaxFreeExitStrategy(),
        new StcgShieldExitStrategy(),
        new StandardExitStrategy(),
        new OverweightStcgShieldStrategy(),
        new TrimOverweightStrategy(),
        new UnderweightBearMarketStrategy(),
        new FillUnderweightStrategy(),
        new WashSaleStrategy(),
        new MeanReversionBuyStrategy(),
        new DefaultHoldStrategy()
    );

    public List<TacticalSignal> computeSignals(RebalanceRequest req) {
        List<TacticalSignal> signals = new ArrayList<>();
        
        Map<String, StrategyTarget> targetMap = req.targets.stream().collect(Collectors.toMap(StrategyTarget::isin, t -> t, (a, b) -> a));
        Set<String> heldIsins = req.holdings.stream().map(AggregatedHolding::getIsin).collect(Collectors.toSet());

        for (AggregatedHolding holding : req.holdings) {
            StrategyTarget target = targetMap.getOrDefault(holding.getIsin(), new StrategyTarget(holding.getIsin(), holding.getSchemeName(), 0.0, 0.0, "DROPPED", "OTHERS"));
            String amfiCode = req.amfiMap.getOrDefault(holding.getIsin(), "");
            MarketMetrics metrics = req.metrics.getOrDefault(amfiCode, MarketMetrics.fromLegacy(50, 0, 0, 0, 0, 0.5, 0, 0, java.time.LocalDate.of(1970, 1, 1)));
            
            RebalanceContext ctx = new RebalanceContext(holding, target, metrics, req, amfiCode);
            for (RebalanceStrategy strategy : strategies) {
                if (strategy.canHandle(ctx)) {
                    signals.add(strategy.evaluate(ctx));
                    break;
                }
            }
        }

        for (StrategyTarget target : req.targets) {
            if (!heldIsins.contains(target.isin()) && target.targetPortfolioPct() > 0) {
                AggregatedHolding holding = AggregatedHolding.builder().isin(target.isin()).schemeName(target.schemeName()).currentValue(java.math.BigDecimal.ZERO).ltcgAmount(java.math.BigDecimal.ZERO).stcgAmount(java.math.BigDecimal.ZERO).daysToNextLtcg(0).build();
                String amfiCode = req.amfiMap.getOrDefault(target.isin(), "");
                MarketMetrics metrics = req.metrics.getOrDefault(amfiCode, MarketMetrics.fromLegacy(50, 0, 0, 0, 0, 0.5, 0, 0, java.time.LocalDate.of(1970, 1, 1)));
                
                RebalanceContext ctx = new RebalanceContext(holding, target, metrics, req, amfiCode);
                for (RebalanceStrategy strategy : strategies) {
                    if (strategy.canHandle(ctx)) {
                        signals.add(strategy.evaluate(ctx));
                        break;
                    }
                }
            }
        }

        return signals;
    }
}
