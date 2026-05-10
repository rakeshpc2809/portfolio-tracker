import { useState, useRef, useMemo, useEffect } from "react";
import { useVirtualizer } from '@tanstack/react-virtual';
import { CalendarDays, Box, Search } from "lucide-react";
import CurrencyValue from "../ui/CurrencyValue";
import { normalizeName } from "../../utils/formatters";
import { useInfiniteTransactions } from "../../hooks/useTransactions";

export default function LedgerView({ 
  investorPan,
  isPrivate 
}: { 
  investorPan: string;
  isPrivate: boolean;
}) {
  const [filterType, setFilterType] = useState("ALL");

  const {
    data,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    isLoading,
  } = useInfiniteTransactions(investorPan, filterType);

  const transactions = useMemo(() => {
    const allTxs = data?.pages.flatMap(page => page.content) || [];
    return allTxs.filter(tx => !tx.transactionType.toUpperCase().includes('STAMP'));
  }, [data]);

  const getMonthYear = (dateStr: string) => {
    const date = new Date(dateStr);
    return date.toLocaleString('default', { month: 'long', year: 'numeric' });
  };

  const isBuy = (type: string) => {
    const t = type.toUpperCase();
    return t.includes("BUY") || t.includes("PURCHASE") || t.includes("SWITCH_IN") || t.includes("SWITCH IN") || t.includes("DIVIDEND_REINVESTMENT") || t.includes("SIP");
  };
  const isSell = (type: string) => {
    const t = type.toUpperCase();
    return t.includes("SELL") || t.includes("REDEMPTION") || t.includes("SWITCH_OUT") || t.includes("SWITCH OUT");
  };

  const monthlySummaries = useMemo(() => {
    const summaries: Record<string, { net: number, count: number }> = {};
    transactions.forEach(tx => {
      const my = getMonthYear(tx.date);
      if (!summaries[my]) summaries[my] = { net: 0, count: 0 };
      const amount = Number(tx.amount);
      if (isBuy(tx.transactionType)) {
        summaries[my].net += amount;
      } else if (isSell(tx.transactionType)) {
        summaries[my].net -= amount;
      }
      summaries[my].count += 1;
    });
    return summaries;
  }, [transactions]);

  // Flattened items for virtualization
  const virtualItems = useMemo(() => {
    const items: Array<{ type: 'header' | 'transaction', data: any, monthYear?: string }> = [];
    transactions.forEach((tx, index) => {
      const currentMonthYear = getMonthYear(tx.date);
      const previousMonthYear = index > 0 ? getMonthYear(transactions[index - 1].date) : null;
      if (currentMonthYear !== previousMonthYear) {
        items.push({ type: 'header', data: monthlySummaries[currentMonthYear], monthYear: currentMonthYear });
      }
      items.push({ type: 'transaction', data: tx });
    });
    return items;
  }, [transactions, monthlySummaries]);

  const parentRef = useRef<HTMLDivElement>(null);
  const rowVirtualizer = useVirtualizer({
    count: virtualItems.length,
    getScrollElement: () => parentRef.current,
    estimateSize: (index) => virtualItems[index].type === 'header' ? 70 : 90,
    overscan: 10,
  });

  useEffect(() => {
    const virtualRows = rowVirtualizer.getVirtualItems();
    if (virtualRows.length === 0) return;
    const lastItem = virtualRows[virtualRows.length - 1];

    if (
      lastItem.index >= virtualItems.length - 1 &&
      hasNextPage &&
      !isFetchingNextPage &&
      !isLoading
    ) {
      fetchNextPage();
    }
  }, [
    hasNextPage,
    fetchNextPage,
    isFetchingNextPage,
    isLoading,
    rowVirtualizer.getVirtualItems(),
    virtualItems.length,
  ]);

  if (isLoading && transactions.length === 0) {
    return (
      <div className="h-96 flex items-center justify-center">
        <div className="w-10 h-10 border-4 border-accent border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

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

      {transactions.length === 0 && !isLoading ? (
        <div className="py-20 text-center bg-surface/20 backdrop-blur-xl border border-dashed border-white/10 rounded-[2.5rem] space-y-4">
          <Search size={40} className="text-muted/10 mx-auto" />
          <p className="text-muted text-[10px] font-black uppercase tracking-widest opacity-40">No transactions found for this filter</p>
        </div>
      ) : (
        <div 
          ref={parentRef}
          className="h-[600px] overflow-auto custom-scrollbar pr-2"
        >
          <div
            style={{
              height: `${rowVirtualizer.getTotalSize()}px`,
              width: '100%',
              position: 'relative',
            }}
          >
            {rowVirtualizer.getVirtualItems().map((virtualRow) => {
              const item = virtualItems[virtualRow.index];
              
              if (item.type === 'header') {
                return (
                  <div
                    key={virtualRow.key}
                    style={{
                      position: 'absolute',
                      top: 0,
                      left: 0,
                      width: '100%',
                      height: `${virtualRow.size}px`,
                      transform: `translateY(${virtualRow.start}px)`,
                    }}
                    className="flex items-center justify-between gap-4 pt-6 pb-2 px-2"
                  >
                    <div className="flex items-center gap-3">
                      <CalendarDays size={14} className="text-accent" />
                      <span className="text-[11px] font-bold text-primary uppercase tracking-[0.2em]">{item.monthYear}</span>
                    </div>
                    <div className="flex items-center gap-4 text-[10px] font-medium">
                      <span className="text-muted uppercase tracking-widest">Net:</span>
                      <CurrencyValue 
                        isPrivate={isPrivate} 
                        value={item.data.net} 
                        className={`tabular-nums font-bold ${item.data.net >= 0 ? 'text-buy' : 'text-exit'}`} 
                      />
                      <span className="text-muted">/</span>
                      <span className="text-muted uppercase tracking-widest">{item.data.count} entries</span>
                    </div>
                  </div>
                );
              }

              const tx = item.data;
              const dateObj = new Date(tx.date);

              return (
                <div
                  key={virtualRow.key}
                  style={{
                    position: 'absolute',
                    top: 0,
                    left: 0,
                    width: '100%',
                    height: `${virtualRow.size}px`,
                    transform: `translateY(${virtualRow.start}px)`,
                  }}
                  className="px-1 py-1"
                >
                  <div className="group bg-surface border border-white/5 p-4 rounded-xl flex items-center hover:bg-white/[0.02] transition-all duration-150 h-full">
                    <div className="w-12 text-center">
                      <p className="text-[10px] font-bold text-muted uppercase mb-0.5">Day</p>
                      <p className="text-sm font-medium text-primary tabular-nums">{dateObj.getDate().toString().padStart(2, '0')}</p>
                    </div>

                    <div className="h-8 w-px bg-white/5 mx-6" />

                    <div className="flex-1 min-w-0">
                      <p className="text-[13px] font-black text-primary truncate mb-1 tracking-tight">
                        {normalizeName(tx.schemeName)}
                      </p>
                      <div className="flex items-center gap-3">
                        <span className={`px-2 py-0.5 rounded-lg text-[9px] font-black uppercase tracking-widest ${
                          isBuy(tx.transactionType) ? 'text-buy bg-buy/10 border border-buy/20' : 
                          isSell(tx.transactionType) ? 'text-exit bg-exit/10 border border-exit/20' : 'text-hint bg-hint/10 border border-white/5'
                        }`}>
                          {tx.transactionType}
                        </span>
                        <span className="text-[10px] text-muted font-black tabular-nums uppercase tracking-[0.15em] flex items-center gap-1 opacity-60">
                          <Box size={10} /> {tx.isin}
                        </span>
                      </div>
                    </div>

                    <div className="text-right ml-8">
                      <p className="text-[10px] font-bold text-muted uppercase mb-0.5">Quantum Delta</p>
                      <p className={`text-sm font-medium tabular-nums ${isBuy(tx.transactionType) ? 'text-buy' : (isSell(tx.transactionType) ? 'text-exit' : 'text-hint')}`}>
                        {isBuy(tx.transactionType) ? '▲' : (isSell(tx.transactionType) ? '▼' : '•')} {tx.units ? tx.units.toFixed(3) : 0}
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
                </div>
              );
            })}
          </div>
          
          {(isFetchingNextPage) && (
            <div className="py-6 flex justify-center">
              <div className="w-5 h-5 border-2 border-accent/30 border-t-accent rounded-full animate-spin" />
            </div>
          )}
        </div>
      )}
    </div>
  );
}
