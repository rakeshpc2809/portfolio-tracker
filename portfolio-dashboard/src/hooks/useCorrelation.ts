import { useQuery } from '@tanstack/react-query';
import { fetchCorrelationMatrix } from '../services/api';

export function useCorrelationMatrix(pan: string) {
  return useQuery({
    queryKey: ['correlation', pan],
    queryFn: () => fetchCorrelationMatrix(pan),
    enabled: !!pan && pan !== 'NEW_USER',
    staleTime: 1000 * 60 * 15, // 15 minutes
  });
}
