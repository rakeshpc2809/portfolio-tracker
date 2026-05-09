import { useState, useEffect } from 'react';
import { motion } from 'framer-motion';
import { 
  Target, 
  Plus, 
  TrendingUp, 
  Calendar, 
  ChevronRight,
  Trophy,
  PieChart as PieIcon
} from 'lucide-react';
import * as Progress from '@radix-ui/react-progress';
import { formatCurrencyShort } from '../../utils/formatters';
import { useDashboardContext } from '../../context/DashboardContext';

interface Goal {
  id?: number;
  name: string;
  targetAmount: number;
  targetDate: string;
  currentAllocation: number;
  priority: 'HIGH' | 'MEDIUM' | 'LOW';
  riskProfile: 'AGGRESSIVE' | 'MODERATE' | 'CONSERVATIVE';
}

export default function GoalsView() {
  const { portfolioData } = useDashboardContext();
  const [goals, setGoals] = useState<Goal[]>([]);

  // In a real app, we'd fetch from the new API
  // For now, let's mock some data to show the UI
  useEffect(() => {
    setGoals([
      { 
        id: 1, 
        name: 'Retirement 2045', 
        targetAmount: 50000000, 
        targetDate: '2045-12-31', 
        currentAllocation: portfolioData?.currentValueAmount || 12500000, 
        priority: 'HIGH',
        riskProfile: 'AGGRESSIVE'
      },
      { 
        id: 2, 
        name: 'Child Education', 
        targetAmount: 10000000, 
        targetDate: '2032-06-01', 
        currentAllocation: 2500000, 
        priority: 'MEDIUM',
        riskProfile: 'MODERATE'
      }
    ]);
  }, [portfolioData]);

  return (
    <div className="space-y-10">
      <header className="flex items-center justify-between">
        <div className="space-y-1">
          <h2 className="text-2xl font-black text-primary tracking-tighter">Goal Architecture</h2>
          <p className="text-[10px] font-black uppercase tracking-[0.3em] text-muted opacity-40">Financial Roadmap & Gaps</p>
        </div>
        <button 
          className="flex items-center gap-2 px-4 py-2 bg-accent/10 border border-accent/20 rounded-xl text-accent hover:bg-accent hover:text-white transition-all group"
        >
          <Plus size={16} className="group-hover:rotate-90 transition-transform" />
          <span className="text-[10px] font-black uppercase tracking-widest">New Goal</span>
        </button>
      </header>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
        {goals.map((goal, idx) => {
          const progress = (goal.currentAllocation / goal.targetAmount) * 100;
          const yearsLeft = new Date(goal.targetDate).getFullYear() - new Date().getFullYear();
          
          return (
            <motion.div 
              key={goal.id}
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: idx * 0.1 }}
              className="bg-surface/40 backdrop-blur-3xl border border-white/5 rounded-[2.5rem] p-8 space-y-8 group hover:border-white/10 transition-all shadow-2xl relative overflow-hidden"
            >
              <div className="absolute top-0 right-0 p-8 opacity-5 group-hover:scale-110 transition-transform duration-700">
                <Target size={120} className="text-accent" />
              </div>

              <div className="flex items-start justify-between relative z-10">
                <div className="space-y-3">
                  <div className="flex items-center gap-2">
                    <span className={`px-2 py-0.5 rounded-md text-[8px] font-black uppercase tracking-widest border ${
                      goal.priority === 'HIGH' ? 'bg-exit/10 border-exit/20 text-exit' : 'bg-accent/10 border-accent/20 text-accent'
                    }`}>
                      {goal.priority} Priority
                    </span>
                    <span className="px-2 py-0.5 bg-white/5 border border-white/10 rounded-md text-[8px] font-black uppercase tracking-widest text-muted">
                      {goal.riskProfile}
                    </span>
                  </div>
                  <h3 className="text-xl font-black text-primary tracking-tight">{goal.name}</h3>
                </div>
                <div className="text-right">
                  <p className="text-[9px] font-black uppercase tracking-widest text-muted opacity-40 mb-1">Time Horizon</p>
                  <div className="flex items-center gap-1.5 text-secondary">
                    <Calendar size={14} className="text-accent" />
                    <span className="text-sm font-black">{yearsLeft} Years</span>
                  </div>
                </div>
              </div>

              <div className="space-y-4 relative z-10">
                <div className="flex justify-between items-end">
                  <div className="space-y-1">
                    <p className="text-[9px] font-black uppercase tracking-widest text-muted opacity-40">Current Funding</p>
                    <p className="text-lg font-black text-primary tracking-tight">{formatCurrencyShort(goal.currentAllocation)}</p>
                  </div>
                  <div className="text-right space-y-1">
                    <p className="text-[9px] font-black uppercase tracking-widest text-muted opacity-40">Target</p>
                    <p className="text-lg font-black text-primary tracking-tight">{formatCurrencyShort(goal.targetAmount)}</p>
                  </div>
                </div>

                <div className="space-y-2">
                  <div className="flex items-center justify-between text-[10px] font-black uppercase tracking-widest">
                    <span className="text-accent">{progress.toFixed(1)}% Funded</span>
                    <span className="text-muted opacity-40">Gap: {formatCurrencyShort(goal.targetAmount - goal.currentAllocation)}</span>
                  </div>
                  <Progress.Root className="h-2 bg-black/40 rounded-full overflow-hidden border border-white/5">
                    <Progress.Indicator 
                      className="h-full bg-accent shadow-[0_0_15px_rgba(129,140,248,0.5)] transition-transform duration-1000 ease-out"
                      style={{ transform: `translateX(-${100 - progress}%)` }}
                    />
                  </Progress.Root>
                </div>
              </div>

              <div className="pt-4 grid grid-cols-2 gap-4 relative z-10">
                <div className="p-4 bg-black/20 rounded-2xl border border-white/5 space-y-1">
                  <p className="text-[8px] font-black uppercase tracking-widest text-muted opacity-60">Req. CAGR</p>
                  <p className="text-xs font-black text-buy">12.4% <TrendingUp size={10} className="inline ml-1" /></p>
                </div>
                <div className="p-4 bg-black/20 rounded-2xl border border-white/5 space-y-1">
                  <p className="text-[8px] font-black uppercase tracking-widest text-muted opacity-60">Monthly SIP</p>
                  <p className="text-xs font-black text-secondary">{formatCurrencyShort(45000)}</p>
                </div>
              </div>

              <button className="w-full py-3 bg-white/5 border border-white/5 rounded-2xl text-[9px] font-black uppercase tracking-widest text-muted hover:bg-white/10 hover:text-primary transition-all flex items-center justify-center gap-2 group/btn relative z-10">
                View Asset Mapping
                <ChevronRight size={14} className="group-hover/btn:translate-x-1 transition-transform" />
              </button>
            </motion.div>
          );
        })}

        <button 
          className="border-2 border-dashed border-white/5 rounded-[2.5rem] flex flex-col items-center justify-center gap-4 p-8 hover:bg-white/[0.02] hover:border-accent/20 transition-all group"
        >
          <div className="w-12 h-12 bg-white/5 rounded-2xl flex items-center justify-center text-muted group-hover:text-accent group-hover:scale-110 transition-all">
            <Plus size={24} />
          </div>
          <div className="text-center space-y-1">
            <p className="text-sm font-black text-muted group-hover:text-primary transition-colors">Add Financial Milestone</p>
            <p className="text-[9px] font-bold text-muted/40 uppercase tracking-widest">Retirement, Education, Home</p>
          </div>
        </button>
      </div>

      <section className="bg-accent/5 border border-accent/10 p-10 rounded-[3rem] space-y-8 relative overflow-hidden">
        <div className="absolute top-0 right-0 p-10 opacity-10">
          <Trophy size={160} className="text-accent" />
        </div>
        
        <div className="flex items-center gap-4">
          <div className="p-3 bg-accent/20 rounded-2xl text-accent">
            <PieIcon size={24} />
          </div>
          <div>
            <h3 className="text-lg font-black text-primary tracking-tight">Aggregated Coverage</h3>
            <p className="text-[10px] font-black uppercase tracking-widest text-muted opacity-60">Portfolio Alignment across all goals</p>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-8 relative z-10">
          <div className="space-y-4">
            <p className="text-xs font-bold text-secondary">Wealth Efficiency</p>
            <div className="text-3xl font-black text-primary">84%</div>
            <p className="text-[10px] text-muted leading-relaxed">Your current asset allocation is highly optimized for your high-priority goals.</p>
          </div>
          <div className="space-y-4">
            <p className="text-xs font-bold text-secondary">Surplus Capital</p>
            <div className="text-3xl font-black text-buy">{formatCurrencyShort(1200000)}</div>
            <p className="text-[10px] text-muted leading-relaxed">Available for opportunistic tactical bets outside of core goal architecture.</p>
          </div>
          <div className="space-y-4">
            <p className="text-xs font-bold text-secondary">Projected 10yr Value</p>
            <div className="text-3xl font-black text-accent">{formatCurrencyShort(85000000)}</div>
            <p className="text-[10px] text-muted leading-relaxed">Based on current conviction scores and historical momentum regimes.</p>
          </div>
        </div>
      </section>
    </div>
  );
}
