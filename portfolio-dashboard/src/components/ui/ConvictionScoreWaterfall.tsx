import { ResponsiveBar } from '@nivo/bar';
import { convictionColor } from '../../utils/formatters';

interface WaterfallProps {
  yieldScore: number;
  riskScore: number;
  valueScore: number;
  painScore: number;
  regimeScore: number;
  frictionScore: number;
  expenseScore: number;
  finalScore: number;
}

export default function ConvictionScoreWaterfall({ 
  yieldScore, riskScore, valueScore, painScore, 
  regimeScore, frictionScore, expenseScore, finalScore 
}: WaterfallProps) {
  
  const factors = [
    { label: 'Yield', score: yieldScore, weight: 0.18 },
    { label: 'Risk', score: riskScore, weight: 0.20 },
    { label: 'Value', score: valueScore, weight: 0.20 },
    { label: 'Pain', score: painScore, weight: 0.15 },
    { label: 'Regime', score: regimeScore, weight: 0.12 },
    { label: 'Friction', score: frictionScore, weight: 0.10 },
    { label: 'Quality', score: expenseScore, weight: 0.05 },
  ];

  let currentSum = 0;
  const data = factors.map(f => {
    const contrib = Math.round(f.score * f.weight);
    const start = currentSum;
    currentSum += contrib;
    return {
      factor: f.label,
      start: start,
      value: contrib,
      displayValue: contrib,
      color: convictionColor(f.score)
    };
  });

  // Add final total bar
  data.push({
    factor: 'Total',
    start: 0,
    value: finalScore,
    displayValue: finalScore,
    color: convictionColor(finalScore)
  });

  return (
    <div className="h-64 w-full">
      <ResponsiveBar
        data={data}
        keys={['start', 'value']}
        indexBy="factor"
        margin={{ top: 20, right: 30, bottom: 50, left: 60 }}
        padding={0.3}
        layout="vertical"
        colors={({ id, data: d }: any) => id === 'start' ? 'transparent' : d.color}
        enableLabel={true}
        label={d => d.id === 'value' ? String(d.data.displayValue) : ''}
        labelTextColor="#ffffff"
        axisLeft={{
          tickSize: 0,
          tickPadding: 10,
          format: v => `${v}`,
        }}
        axisBottom={{
          tickSize: 0,
          tickPadding: 10,
        }}
        theme={{
          axis: { ticks: { text: { fill: "#6c7086", fontSize: 10, fontWeight: 700 } } },
          grid: { line: { stroke: "rgba(255,255,255,0.05)" } },
          tooltip: { container: { background: "#181825", color: "#cdd6f4", fontSize: 11, borderRadius: 12 } }
        }}
        tooltip={({ data: d, id }) => {
          if (id === 'start') return null;
          return (
            <div className="bg-surface-overlay/95 backdrop-blur-xl border border-white/10 p-3 rounded-xl shadow-2xl">
              <p className="text-[10px] font-black uppercase tracking-widest text-muted mb-1">{d.factor}</p>
              <p className="text-sm font-black text-primary">Contribution: {d.displayValue} pts</p>
            </div>
          );
        }}
      />
    </div>
  );
}
