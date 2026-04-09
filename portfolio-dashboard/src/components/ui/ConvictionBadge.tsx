export default function ConvictionBadge({ score }: { score: number }) {
  const s = score ?? 0;
  const isHigh = s >= 65;
  const isMid  = s >= 45 && s < 65;

  const ring   = isHigh ? 'border-emerald-400/30' : isMid ? 'border-amber-400/30' : 'border-red-400/30';
  const text   = isHigh ? 'text-emerald-400'       : isMid ? 'text-amber-400'       : 'text-red-400';
  const bg     = isHigh ? 'bg-emerald-400/8'        : isMid ? 'bg-amber-400/8'        : 'bg-red-400/8';
  const label  = isHigh ? 'HIGH'                    : isMid ? 'MID'                    : 'LOW';

  return (
    <div className={`inline-flex items-center gap-1.5 px-2 py-1 rounded-lg border ${ring} ${bg}`}>
      {/* mini arc indicator */}
      <svg width="18" height="10" viewBox="0 0 18 10" fill="none" className="shrink-0">
        <path
          d="M1 9 A8 8 0 0 1 17 9"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          className="text-white/10"
        />
        <path
          d="M1 9 A8 8 0 0 1 17 9"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeDasharray={`${(s / 100) * 25.1} 25.1`}
          className={text}
        />
      </svg>
      <span className={`text-[10px] font-bold tabular-nums ${text}`}>{s}</span>
      <span className={`text-[8px] font-bold uppercase tracking-wider opacity-60 ${text}`}>{label}</span>
    </div>
  );
}
