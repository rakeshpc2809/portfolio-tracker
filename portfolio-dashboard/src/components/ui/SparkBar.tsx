/**
 * SparkBar — 8-column mini bar chart for inline use.
 * Pass `values`: array of 2–8 numbers. Renders relative bars.
 */
export default function SparkBar({
  values,
  color = '#818cf8',
  height = 20,
}: {
  values: number[];
  color?: string;
  height?: number;
}) {
  if (!values || values.length === 0) return null;
  const max = Math.max(...values, 0.01);
  const barW = 3;
  const gap = 1.5;
  const totalW = values.length * (barW + gap) - gap;

  return (
    <svg width={totalW} height={height} viewBox={`0 0 ${totalW} ${height}`} className="shrink-0">
      {values.map((v, i) => {
        const barH = Math.max(2, (v / max) * height);
        const x = i * (barW + gap);
        const y = height - barH;
        const opacity = 0.4 + (i / values.length) * 0.6; // fade in left to right
        return (
          <rect
            key={i}
            x={x} y={y} width={barW} height={barH}
            rx="1"
            fill={color}
            opacity={opacity}
          />
        );
      })}
    </svg>
  );
}
