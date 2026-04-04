import React, { useState, useEffect, useRef, useCallback, useMemo } from "react";
import { 
  ArrowUpRight, ArrowDownLeft, Receipt, Box, CalendarDays, ChevronRight
} from "lucide-react";
import { formatCurrency } from "../../utils/formatters";

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



// ... Interface remains the same ...

export default function TransactionsTab({ investorPan }: { investorPan: string }) {
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

  // 🗓️ Extract unique years from loaded transactions for the Navigator
  const availableYears = useMemo(() => {
    const years = transactions.map(t => new Date(t.date).getFullYear());
    return Array.from(new Set(years)).sort((a, b) => b - a);
  }, [transactions]);

  // 🧮 Monthly Summary Logic
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
      const typeParam = type === "ALL" ? "" : `&type=${type}`;
      const response = await fetch(
        `http://localhost:8080/api/transactions?pan=${investorPan}${typeParam}&page=${pageNum}&size=20&sort=date,desc`
      );
      const data = await response.json();
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

  // 🎯 Scroll to specific year logic
  const scrollToYear = (year: number) => {
    const element = document.getElementById(`year-${year}`);
    if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  };

  return (
    <div className="flex flex-col lg:flex-row gap-8 relative">
      
      {/* 📜 MAIN LEDGER COLUMN */}
      <div className="flex-1 space-y-6 animate-in fade-in duration-700 pb-20">
        
        {/* CONTROL CONSOLE */}
        <div className="flex flex-col md:flex-row justify-between items-center gap-4 bg-zinc-900/40 border border-zinc-800/50 p-6 rounded-[2.5rem] backdrop-blur-xl">
          <div className="flex items-center gap-4">
            <div className="p-3 bg-blue-500/10 rounded-2xl border border-blue-500/20">
              <Receipt className="text-blue-500" size={20} />
            </div>
            <div>
              <h3 className="text-[10px] font-black text-zinc-400 uppercase tracking-[0.3em]">Event Ledger</h3>
              <p className="text-[8px] text-zinc-600 font-bold uppercase italic tracking-widest">Chronological Node Stream</p>
            </div>
          </div>

          <div className="flex bg-black/40 p-1.5 rounded-2xl border border-zinc-800/50">
            {["ALL", "BUY", "SELL"].map((t) => (
              <button
                key={t}
                onClick={() => setFilterType(t)}
                className={`px-8 py-2 rounded-xl text-[9px] font-black uppercase transition-all duration-300 ${
                  filterType === t ? "bg-zinc-100 text-black shadow-lg" : "text-zinc-500 hover:text-zinc-300"
                }`}
              >
                {t}
              </button>
            ))}
          </div>
        </div>

        {/* TRANSACTION STREAM */}
        <div className="space-y-1">
          {transactions.map((tx, index) => {
            const dateObj = new Date(tx.date);
            const currentMonthYear = getMonthYear(tx.date);
            const currentYear = dateObj.getFullYear();
            
            const previousMonthYear = index > 0 ? getMonthYear(transactions[index - 1].date) : null;
            const previousYear = index > 0 ? new Date(transactions[index - 1].date).getFullYear() : null;
            
            const isNewMonth = currentMonthYear !== previousMonthYear;
            const isNewYear = currentYear !== previousYear;
            const monthStats = monthlySummaries[currentMonthYear];

            return (
              <React.Fragment key={`${tx.txnHash}-${index}`}>
                {/* 🎯 YEAR ANCHOR (Hidden for scroll targeting) */}
                {isNewYear && <div id={`year-${currentYear}`} className="scroll-mt-24" />}

                {/* MONTH HEADER */}
                {isNewMonth && (
                  <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pt-10 pb-6 px-6">
                    <div className="flex items-center gap-4 flex-1">
                      <span className="text-[11px] font-black text-white uppercase tracking-[0.4em] flex items-center gap-3">
                        <CalendarDays size={14} className="text-blue-500" /> {currentMonthYear}
                      </span>
                      <div className="h-px flex-1 bg-gradient-to-r from-zinc-800 to-transparent" />
                    </div>
                    
                    <div className="flex items-center gap-6 bg-zinc-900/40 border border-zinc-800/60 px-5 py-2 rounded-2xl">
                       <div className="flex flex-col items-end">
                          <span className="text-[7px] font-black text-zinc-500 uppercase tracking-widest italic">Net Flow</span>
                          <span className={`text-[10px] font-mono font-black ${monthStats.net >= 0 ? 'text-emerald-500' : 'text-rose-500'}`}>
                             {monthStats.net >= 0 ? '+' : ''}{formatCurrency(monthStats.net)}
                          </span>
                       </div>
                    </div>
                  </div>
                )}

                {/* TRANSACTION ROW */}
                <div 
                  ref={index === transactions.length - 1 ? lastElementRef : null}
                  className="group bg-zinc-900/10 border border-zinc-800/30 p-6 rounded-[2.5rem] flex flex-col md:flex-row items-center hover:bg-zinc-900/30 hover:border-blue-500/30 transition-all duration-500 relative mb-3"
                >
                  <div className="flex flex-col items-center justify-center bg-zinc-800/30 border border-zinc-700/30 px-5 py-2 rounded-2xl mb-4 md:mb-0 md:mr-8 min-w-[80px]">
                     <span className="text-[7px] font-black text-zinc-600 uppercase tracking-widest">Day</span>
                     <span className="text-lg font-mono font-black text-white tracking-tighter">
                       {dateObj.getDate().toString().padStart(2, '0')}
                     </span>
                  </div>

                  <div className="flex flex-1 items-center gap-6 w-full md:w-auto">
                    <div className={`p-4 rounded-2xl border ${
                      tx.transactionType === 'BUY' ? 'bg-emerald-500/5 border-emerald-500/10 text-emerald-500' : 'bg-rose-500/5 border-rose-500/10 text-rose-500'
                    }`}>
                      {tx.transactionType === 'BUY' ? <ArrowUpRight size={24} /> : <ArrowDownLeft size={24} />}
                    </div>
                    <div className="max-w-md">
                      <p className="text-[12px] font-black text-zinc-200 uppercase tracking-tighter mb-1 truncate leading-tight">{tx.schemeName}</p>
                      <div className="flex items-center gap-4 text-[8px] font-bold text-zinc-600 uppercase tracking-[0.15em]">
                        <span className="flex items-center gap-1.5"><Box size={10} className="text-zinc-700"/> {tx.isin}</span>
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center gap-12 mt-4 md:mt-0 w-full md:w-auto justify-between md:justify-end border-t md:border-t-0 border-zinc-800/50 pt-4 md:pt-0">
                     <div className="text-right">
                        <p className="text-[7px] font-black text-zinc-500 uppercase mb-1 tracking-widest italic">Quantum Delta</p>
                        <p className={`text-md font-mono font-bold italic ${tx.transactionType === 'BUY' ? 'text-emerald-400' : 'text-rose-400'}`}>
                          {tx.transactionType === 'BUY' ? '▲' : '▼'} {tx.units.toFixed(3)}
                        </p>
                     </div>
                     <div className="text-right min-w-[150px]">
                        <p className="text-[7px] font-black text-zinc-500 uppercase mb-1 tracking-widest">Settlement</p>
                        <p className="text-xl font-black tracking-tighter text-white">{formatCurrency(tx.amount)}</p>
                     </div>
                  </div>
                </div>
              </React.Fragment>
            );
          })}

          {loading && (
            <div className="py-12 flex flex-col items-center gap-3 animate-pulse">
              <div className="w-1 h-12 bg-blue-500/20 rounded-full overflow-hidden">
                 <div className="h-full bg-blue-500 animate-bounce" />
              </div>
            </div>
          )}
        </div>
      </div>

      {/* 🏎️ TEMPORAL NAVIGATOR (SIDEBAR) */}
      <div className="hidden lg:block w-32 sticky top-32 h-fit">
        <p className="text-[8px] font-black text-zinc-700 uppercase tracking-[0.3em] mb-4 pl-4">Navigate</p>
        <div className="space-y-1">
          {availableYears.map(year => (
            <button
              key={year}
              onClick={() => scrollToYear(year)}
              className="w-full flex items-center justify-between px-4 py-3 rounded-xl border border-transparent hover:border-zinc-800 hover:bg-zinc-900/50 transition-all group"
            >
              <span className="text-[11px] font-black text-zinc-500 group-hover:text-blue-400 transition-colors">{year}</span>
              <ChevronRight size={10} className="text-zinc-800 group-hover:text-blue-500" />
            </button>
          ))}
        </div>
        <div className="mt-8 pt-8 border-t border-zinc-900">
           <div className="px-4 py-3 bg-blue-500/5 border border-blue-500/10 rounded-xl">
              <p className="text-[7px] font-black text-blue-500/60 uppercase mb-1">Total Entries</p>
              <p className="text-xs font-black text-zinc-200">{transactions.length}</p>
           </div>
        </div>
      </div>

    </div>
  );
}