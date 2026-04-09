/**
 * StatusRing — circular health/score arc indicator.
 * score: 0–100
 */
export default function StatusRing({ score, size = 32 }: { score: number; size?: number }) {
  const r = (size - 4) / 2;
  const circ = 2 * Math.PI * r;
  const filled = (score / 100) * circ;
  const color = score >= 65 ? '#34d399' : score >= 45 ? '#fbbf24' : '#f87171';

  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="-rotate-90 shrink-0">
      {/* track */}
      <circle cx={size / 2} cy={size / 2} r={r}
        stroke="rgba(255,255,255,0.07)" strokeWidth="2.5" fill="none" />
      {/* fill */}
      <circle cx={size / 2} cy={size / 2} r={r}
        stroke={color} strokeWidth="2.5" fill="none"
        strokeDasharray={`${filled} ${circ}`}
        strokeLinecap="round"
        style={{ transition: 'stroke-dasharray 0.8s ease' }}
      />
    </svg>
  );
}
