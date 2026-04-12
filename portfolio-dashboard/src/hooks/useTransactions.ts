import { useInfiniteQuery } from '@tanstack/react-query';
import { fetchTransactions } from '../services/api';

export function useInfiniteTransactions(pan: string, type: string) {
  return useInfiniteQuery({
    queryKey: ['transactions', pan, type],
    queryFn: ({ pageParam = 0 }) => fetchTransactions(pan, pageParam, type),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => {
      if (lastPage.last) return undefined;
      return lastPage.number + 1;
    },
    enabled: !!pan && pan !== 'NEW_USER',
    staleTime: 1000 * 60 * 5, // 5 minutes
  });
}
