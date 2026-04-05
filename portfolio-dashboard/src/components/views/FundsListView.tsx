import { useState } from 'react';
import { Search, ChevronRight } from 'lucide-react';
import ConvictionBadge from '../ui/ConvictionBadge';
import CurrencyValue from '../ui/CurrencyValue';

export default function FundsListView({ 
  portfolioData, 
  onFundClick,
  isPrivate
}: { 
  portfolioData: any;
  onFundClick: (schemeName: string) => void;
  isPrivate: boolean;
}) {
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('ALL');

  const funds = (portfolioData.schemeBreakdown || []).filter((f: any) => {
    const matchesSearch = f.schemeName.toLowerCase().includes(search.toLowerCase());
    const matchesFilter = filter === 'ALL' || f.category?.toUpperCase().includes(filter);
    return matchesSearch && matchesFilter;
  });

  const categories = ['ALL', 'EQUITY', 'DEBT', 'GOLD', 'ARBITRAGE'];

  return (
    <div className="space-y-8 pb-32">
      <header className="flex flex-col md:flex-row md:items-center justify-between gap-6">
        <div>
          <h2 className="text-muted text-[10px] font-medium uppercase tracking-[0.2em] mb-1">Asset matrix</h2>
          <p className="text-xl font-medium text-primary tracking-tight">Your holdings · {portfolioData.schemeBreakdown?.length || 0} funds</p>
        </div>
        
        <div className="flex flex-wrap items-center gap-4">
          <div className="relative">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
            <input 
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search funds..."
              className="bg-surface border border-white/5 rounded-lg pl-10 pr-4 py-2 text-sm text-primary focus:outline-none focus:border-accent/50 transition-colors w-64"
            />
          </div>
          <div className="flex bg-surface border border-white/5 p-1 rounded-lg">
            {categories.map((cat) => (
              <button
                key={cat}
                onClick={() => setFilter(cat)}
                className={`px-4 py-1.5 rounded-md text-[10px] font-bold uppercase tracking-widest transition-all duration-200 ${
                  filter === cat ? "bg-white/10 text-primary" : "text-muted hover:text-secondary"
                }`}
              >
                {cat}
              </button>
            ))}
          </div>
        </div>
      </header>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {funds.map((fund: any) => (
          <div 
            key={fund.schemeName}
            onClick={() => onFundClick(fund.schemeName)}
            className="bg-surface border border-white/5 p-5 rounded-xl hover:bg-white/[0.02] hover:border-white/10 transition-all duration-150 cursor-pointer group"
          >
            <div className="flex items-start justify-between mb-4">
              <div className="space-y-1">
                <p className="text-[10px] font-bold text-muted uppercase tracking-widest">{fund.category}</p>
                <h3 className="text-[13px] font-medium text-primary group-hover:text-white transition-colors line-clamp-1">{fund.schemeName}</h3>
              </div>
              <ConvictionBadge score={fund.convictionScore} />
            </div>

            <div className="grid grid-cols-2 gap-4 mb-6">
              <div>
                <p className="text-[9px] uppercase tracking-widest text-muted mb-1">Value</p>
                <div className="text-sm font-medium tabular-nums text-primary">
                  <CurrencyValue isPrivate={isPrivate} value={fund.currentValue} />
                </div>
              </div>
              <div className="text-right">
                <p className="text-[9px] uppercase tracking-widest text-muted mb-1">XIRR</p>
                <p className={`text-sm font-medium tabular-nums ${parseFloat(fund.xirr) >= 0 ? 'text-buy' : 'text-exit'}`}>{fund.xirr}</p>
              </div>
            </div>

            <div className="flex items-center justify-between pt-4 border-t border-white/5">
              <span className={`text-[10px] font-bold uppercase tracking-widest ${
                fund.action === 'BUY' ? 'text-buy' : 
                fund.action === 'EXIT' ? 'text-exit' : 'text-hold'
              }`}>
                {fund.action}
              </span>
              <div className="flex items-center gap-1 text-[10px] text-muted font-medium group-hover:text-secondary transition-colors">
                Details <ChevronRight size={12} />
              </div>
            </div>
          </div>
        ))}
      </div>

      {funds.length === 0 && (
        <div className="py-32 flex flex-col items-center justify-center bg-surface/50 border border-dashed border-white/5 rounded-xl">
          <p className="text-muted text-xs font-medium">No funds found matching your criteria.</p>
        </div>
      )}
    </div>
  );
}
