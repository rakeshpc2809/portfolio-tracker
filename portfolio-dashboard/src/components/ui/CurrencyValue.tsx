import { formatCurrency, formatCurrencyShort } from '../../utils/formatters';

export default function CurrencyValue({ 
  value, 
  isPrivate, 
  short = false,
  className = ""
}: { 
  value: number | string; 
  isPrivate: boolean; 
  short?: boolean;
  className?: string;
}) {
  if (isPrivate) {
    return <span className={className}>••••</span>;
  }

  const numValue = typeof value === 'string' ? parseFloat(value) : value;
  return (
    <span className={className}>
      {short ? formatCurrencyShort(numValue) : formatCurrency(numValue)}
    </span>
  );
}
