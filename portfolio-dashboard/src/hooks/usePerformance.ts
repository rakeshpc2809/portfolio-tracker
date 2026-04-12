import { useQuery } from '@tanstack/react-query';
import { fetchPerformanceData } from '../services/api';

export function usePerformanceHistory(pan: string) {
  return useQuery({
    queryKey: ['performance', pan],
    queryFn: () => fetchPerformanceData(pan),
    enabled: !!pan && pan !== 'NEW_USER',
    staleTime: 1000 * 60 * 10, // 10 minutes
  });
}
