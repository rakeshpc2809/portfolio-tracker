import { useQuery } from '@tanstack/react-query';
import { fetchMasterPortfolio } from '../services/api';

export function usePortfolioSummary(pan: string | null, sip: number, lumpsum: number) {
  return useQuery({
    queryKey: ['portfolio', pan, sip, lumpsum],
    queryFn: () => fetchMasterPortfolio(pan!, sip, lumpsum),
    enabled: !!pan && pan !== 'SETUP',
    staleTime: 1000 * 60 * 5, // 5 minutes
  });
}
