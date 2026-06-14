import { useMemo } from 'react';
import * as Tooltip from '@radix-ui/react-tooltip';
import { 
  Table, 
  TableHeader, 
  TableBody, 
  TableHead, 
  TableRow, 
  TableCell 
} from './table';
import type { TacticalSignal } from '../../types/signals';

interface FundsTableProps {
  allSignals: TacticalSignal[];
  schemeBreakdown: any[];
}

const formatHurst = (val: number): string => {
  if (val < 0.45) return 'MEAN REVERTING';
  if (val > 0.55) return 'TRENDING';
  return 'RANDOM WALK';
};

export default function FundsTable({ allSignals, schemeBreakdown }: FundsTableProps) {
  const xirrMap = useMemo(() => {
    const map = new Map<string, string>();
    schemeBreakdown.forEach((s) => {
      if (s.amfiCode) {
        map.set(s.amfiCode, s.xirr || '0.0%');
      }
    });
    return map;
  }, [schemeBreakdown]);

  const sortedSignals = useMemo(() => {
    return [...allSignals].sort((a, b) => {
      // Sort BUY first, then SELL, then others
      const priority: Record<string, number> = { BUY: 0, SELL: 1, EXIT: 2, WATCH: 3, HOLD: 4 };
      const priorityA = priority[a.action] ?? 5;
      const priorityB = priority[b.action] ?? 5;
      if (priorityA !== priorityB) return priorityA - priorityB;
      return a.schemeName.localeCompare(b.schemeName);
    });
  }, [allSignals]);

  const getActionStyles = (action: string) => {
    switch (action) {
      case 'BUY':
        return 'text-green-400 bg-green-500/10 border-green-500/20';
      case 'SELL':
        return 'text-red-400 bg-red-500/10 border-red-500/20';
      case 'EXIT':
        return 'text-amber-400 bg-amber-500/10 border-amber-500/20';
      case 'WATCH':
        return 'text-cyan-400 bg-cyan-500/10 border-cyan-500/20';
      default:
        return 'text-slate-400 bg-slate-500/5 border-slate-500/10';
    }
  };

  return (
    <div className="border border-white/5 rounded-lg overflow-hidden bg-surface/25 backdrop-blur-md">
      <Table>
        <TableHeader>
          <TableRow className="border-white/5 bg-white/5 hover:bg-white/5">
            <TableHead className="py-2.5 px-3 text-[10px] font-black uppercase tracking-wider text-slate-400">Fund Name</TableHead>
            <TableHead className="py-2.5 px-3 text-[10px] font-black uppercase tracking-wider text-slate-400 text-right">Target %</TableHead>
            <TableHead className="py-2.5 px-3 text-[10px] font-black uppercase tracking-wider text-slate-400 text-right">Current %</TableHead>
            <TableHead className="py-2.5 px-3 text-[10px] font-black uppercase tracking-wider text-slate-400 text-right">Absolute Return</TableHead>
            <TableHead className="py-2.5 px-3 text-[10px] font-black uppercase tracking-wider text-slate-400 text-right">Z-Score</TableHead>
            <TableHead className="py-2.5 px-3 text-[10px] font-black uppercase tracking-wider text-slate-400 text-right">Trend (Hurst)</TableHead>
            <TableHead className="py-2.5 px-3 text-[10px] font-black uppercase tracking-wider text-slate-400 text-center">Action</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {sortedSignals.map((sig) => {
            const xirr = xirrMap.get(sig.amfiCode) || '0.0%';
            const zScoreVal = sig.returnZScore !== undefined ? sig.returnZScore : 0.0;
            const hurstVal = sig.hurstExponent !== undefined ? sig.hurstExponent : 0.5;
            const justification = sig.justifications && sig.justifications.length > 0 
              ? sig.justifications[0] 
              : 'Hold: Fund is within target range.';

            return (
              <TableRow key={sig.amfiCode} className="border-white/5 hover:bg-white/5/20 transition-none">
                <TableCell className="py-2 px-3 text-xs font-bold text-slate-200 truncate max-w-xs md:max-w-md">
                  {sig.simpleName || sig.schemeName}
                </TableCell>
                <TableCell className="py-2 px-3 text-xs font-bold text-slate-300 text-right tabular-nums">
                  {sig.plannedPercentage.toFixed(2)}%
                </TableCell>
                <TableCell className="py-2 px-3 text-xs font-bold text-slate-300 text-right tabular-nums">
                  {sig.actualPercentage.toFixed(2)}%
                </TableCell>
                <TableCell className="py-2 px-3 text-xs font-bold text-slate-300 text-right tabular-nums">
                  {xirr}
                </TableCell>
                <TableCell className="py-2 px-3 text-xs font-bold text-slate-300 text-right tabular-nums">
                  {zScoreVal.toFixed(2)}
                </TableCell>
                <TableCell className="py-2 px-3 text-xs font-bold text-slate-300 text-right">
                  {formatHurst(hurstVal)}
                </TableCell>
                <TableCell className="py-2 px-3 text-center">
                  <Tooltip.Provider delayDuration={100}>
                    <Tooltip.Root>
                      <Tooltip.Trigger asChild>
                        <span className={`inline-block px-2.5 py-0.5 rounded text-[10px] font-black tracking-widest border cursor-help ${getActionStyles(sig.action)}`}>
                          {sig.action}
                        </span>
                      </Tooltip.Trigger>
                      <Tooltip.Portal>
                        <Tooltip.Content 
                          className="bg-slate-950 border border-white/10 rounded-lg p-2.5 shadow-2xl max-w-sm text-xs text-slate-200 z-50 transition-none"
                          sideOffset={4}
                        >
                          <div className="space-y-1">
                            <p className="font-bold text-primary">Signal Justification</p>
                            <p className="opacity-90 leading-relaxed">{justification}</p>
                          </div>
                          <Tooltip.Arrow className="fill-white/10" />
                        </Tooltip.Content>
                      </Tooltip.Portal>
                    </Tooltip.Root>
                  </Tooltip.Provider>
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </div>
  );
}
