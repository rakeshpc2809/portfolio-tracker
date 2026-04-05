export default function ConvictionBadge({ score }: { score: number }) {
  const color = score >= 65 ? 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20'
              : score >= 45 ? 'text-amber-400 bg-amber-400/10 border-amber-400/20'
              : 'text-red-400 bg-red-400/10 border-red-400/20';
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-md border text-[10px] font-medium tabular-nums ${color}`}>
      {score}
    </span>
  );
}
