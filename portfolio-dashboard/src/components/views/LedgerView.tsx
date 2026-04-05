import React, { useState, useEffect, useRef, useCallback, useMemo } from "react";
import { CalendarDays, Box } from "lucide-react";
import { fetchTransactions } from "../../services/api";
import CurrencyValue from "../ui/CurrencyValue";

interface TransactionDTO {
  id: number;
  txnHash: string;
  date: string;
  description: string;
  units: number;
  amount: number;
  transactionType: string;
  schemeName: string;
  isin: string;
}

export default function LedgerView({ 
  investorPan,
  isPrivate 
}: { 
  investorPan: string;
  isPrivate: boolean;
}) {
  const [transactions, setTransactions] = useState<TransactionDTO[]>([]);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [hasMore, setHasMore] = useState(true);
  const [filterType, setFilterType] = useState("ALL");

  const observer = useRef<IntersectionObserver | null>(null);

  const getMonthYear = (dateStr: string) => {
    const date = new Date(dateStr);
    return date.toLocaleString('default', { month: 'long', year: 'numeric' });
  };

  const monthlySummaries = useMemo(() => {
    const summaries: Record<string, { net: number, count: number }> = {};
    transactions.forEach(tx => {
      const my = getMonthYear(tx.date);
      if (!summaries[my]) summaries[my] = { net: 0, count: 0 };
      const amount = Number(tx.amount);
      summaries[my].net += (tx.transactionType === 'BUY' ? amount : -amount);
      summaries[my].count += 1;
    });
    return summaries;
  }, [transactions]);

  const lastElementRef = useCallback((node: HTMLDivElement) => {
    if (loading) return;
    if (observer.current) observer.current.disconnect();
    observer.current = new IntersectionObserver(entries => {
      if (entries[0].isIntersecting && hasMore) {
        setPage(prev => prev + 1);
      }
    });
    if (node) observer.current.observe(node);
  }, [loading, hasMore]);

  const fetchLedger = async (pageNum: number, type: string) => {
    setLoading(true);
    try {
      const data = await fetchTransactions(investorPan, pageNum, type);
      setTransactions(prev => (pageNum === 0 ? data.content : [...prev, ...data.content]));
      setHasMore(!data.last);
    } catch (err) {
      console.error("Ledger Sync Error:", err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setPage(0);
    fetchLedger(0, filterType);
  }, [filterType, investorPan]);

  useEffect(() => {
    if (page > 0) fetchLedger(page, filterType);
  }, [page]);

  return (
    <div className="space-y-8 pb-32">
      <header className="flex flex-col md:flex-row md:items-center justify-between gap-6">
        <div>
          <h2 className="text-muted text-[10px] font-medium uppercase tracking-[0.2em] mb-1">Audit log</h2>
          <p className="text-xl font-medium text-primary tracking-tight">Transaction history</p>
        </div>
        
        <div className="flex bg-surface border border-white/5 p-1 rounded-lg">
          {["ALL", "BUY", "SELL"].map((t) => (
            <button
              key={t}
              onClick={() => setFilterType(t)}
              className={`px-6 py-1.5 rounded-md text-[10px] font-bold uppercase tracking-widest transition-all duration-200 ${
                filterType === t ? "bg-white/10 text-primary shadow-sm" : "text-muted hover:text-secondary"
              }`}
            >
              {t}
            </button>
          ))}
        </div>
      </header>

      <div className="space-y-4">
        {transactions.map((tx, index) => {
          const dateObj = new Date(tx.date);
          const currentMonthYear = getMonthYear(tx.date);
          const previousMonthYear = index > 0 ? getMonthYear(transactions[index - 1].date) : null;
          const isNewMonth = currentMonthYear !== previousMonthYear;
          const monthStats = monthlySummaries[currentMonthYear];

          return (
            <React.Fragment key={`${tx.txnHash}-${index}`}>
              {isNewMonth && (
                <div className="flex items-center justify-between gap-4 pt-10 pb-4 px-2">
                  <div className="flex items-center gap-3">
                    <CalendarDays size={14} className="text-accent" />
                    <span className="text-[11px] font-bold text-primary uppercase tracking-[0.2em]">{currentMonthYear}</span>
                  </div>
                  <div className="flex items-center gap-4 text-[10px] font-medium">
                    <span className="text-muted uppercase tracking-widest">Net invested:</span>
                    <CurrencyValue 
                      isPrivate={isPrivate} 
                      value={monthStats.net} 
                      className={`tabular-nums font-bold ${monthStats.net >= 0 ? 'text-buy' : 'text-exit'}`} 
                    />
                    <span className="text-muted">/</span>
                    <span className="text-muted uppercase tracking-widest">{monthStats.count} entries</span>
                  </div>
                </div>
              )}

              <div 
                ref={index === transactions.length - 1 ? lastElementRef : null}
                className="group bg-surface border border-white/5 p-4 rounded-xl flex items-center hover:bg-white/[0.02] transition-all duration-150"
              >
                <div className="w-12 text-center">
                  <p className="text-[10px] font-bold text-muted uppercase mb-0.5">Day</p>
                  <p className="text-sm font-medium text-primary tabular-nums">{dateObj.getDate().toString().padStart(2, '0')}</p>
                </div>

                <div className="h-8 w-px bg-white/5 mx-6" />

                <div className="flex-1 min-w-0">
                  <p className="text-[13px] font-medium text-primary truncate mb-1">{tx.schemeName}</p>
                  <div className="flex items-center gap-3">
                    <span className={`px-1.5 py-0.5 rounded text-[9px] font-bold uppercase tracking-tighter ${
                      tx.transactionType === 'BUY' ? 'text-buy bg-buy/10' : 
                      tx.transactionType === 'SELL' ? 'text-exit bg-exit/10' : 'text-hold bg-hold/10'
                    }`}>
                      {tx.transactionType}
                    </span>
                    <span className="text-[10px] text-muted font-medium tabular-nums uppercase tracking-widest flex items-center gap-1">
                      <Box size={10} /> {tx.isin}
                    </span>
                  </div>
                </div>

                <div className="text-right ml-8">
                  <p className="text-[10px] font-bold text-muted uppercase mb-0.5">Quantum Delta</p>
                  <p className={`text-sm font-medium tabular-nums ${tx.transactionType === 'BUY' ? 'text-buy' : 'text-exit'}`}>
                    {tx.transactionType === 'BUY' ? '▲' : '▼'} {tx.units.toFixed(3)}
                  </p>
                </div>

                <div className="text-right ml-12 min-w-[120px]">
                  <p className="text-[10px] font-bold text-muted uppercase mb-0.5">Settlement</p>
                  <CurrencyValue 
                    isPrivate={isPrivate} 
                    value={tx.amount} 
                    className="text-sm font-medium text-primary tabular-nums block" 
                  />
                </div>
              </div>
            </React.Fragment>
          );
        })}

        {loading && (
          <div className="py-12 flex justify-center">
            <div className="w-5 h-5 border-2 border-accent/30 border-t-accent rounded-full animate-spin" />
          </div>
        )}
      </div>
    </div>
  );
}
