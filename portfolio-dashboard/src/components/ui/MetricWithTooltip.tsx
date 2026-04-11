import { type ReactNode } from 'react';
import * as Tooltip from '@radix-ui/react-tooltip';

export default function MetricWithTooltip({
  label, value, tooltip, valueClass = ''
}: { label: ReactNode; value: ReactNode; tooltip: string; valueClass?: string }) {
  return (
    <Tooltip.Provider delayDuration={200}>
      <Tooltip.Root>
        <Tooltip.Trigger asChild>
          <div className="cursor-help">
            <p className="text-[10px] uppercase tracking-widest text-slate-500 mb-0.5">{label}</p>
            <div className={`text-lg font-medium tabular-nums text-slate-100 ${valueClass}`}>{value}</div>
          </div>
        </Tooltip.Trigger>
        <Tooltip.Portal>
          <Tooltip.Content className="bg-[#1a1a2e] border border-white/10 text-slate-300 text-[11px] rounded-lg px-3 py-2 max-w-[220px] leading-relaxed z-[100] shadow-xl animate-in fade-in zoom-in-95 duration-150">
            {tooltip}
            <Tooltip.Arrow className="fill-[#1a1a2e]" />
          </Tooltip.Content>
        </Tooltip.Portal>
      </Tooltip.Root>
    </Tooltip.Provider>
  );
}
