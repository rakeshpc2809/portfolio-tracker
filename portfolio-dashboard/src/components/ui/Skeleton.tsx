export const Skeleton = ({ className }: { className?: string }) => (
  <div
    className={`animate-pulse rounded-xl ${className}`}
    style={{ background: 'rgba(255,255,255,0.04)' }}
  />
);

export const StatCardSkeleton = () => (
  <div className="bg-surface border border-white/5 p-6 rounded-xl space-y-3">
    <Skeleton className="h-2.5 w-20" />
    <Skeleton className="h-6 w-28" />
  </div>
);

export const FundCardSkeleton = () => (
  <div className="bg-surface border border-white/[0.06] rounded-xl p-5 space-y-4">
    <div className="flex justify-between">
      <div className="space-y-2">
        <Skeleton className="h-2 w-16" />
        <Skeleton className="h-3.5 w-36" />
        <Skeleton className="h-3.5 w-24" />
      </div>
      <Skeleton className="h-8 w-16" />
    </div>
    <Skeleton className="h-1.5 w-full" />
    <div className="grid grid-cols-3 gap-3">
      {[...Array(6)].map((_, i) => (
        <div key={i} className="space-y-1.5">
          <Skeleton className="h-2 w-10" />
          <Skeleton className="h-3 w-14" />
        </div>
      ))}
    </div>
    <Skeleton className="h-1 w-full" />
  </div>
);

export const OverviewSkeleton = () => (
  <div className="space-y-8 pb-32">
    <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
      {[...Array(4)].map((_, i) => <StatCardSkeleton key={i} />)}
    </div>
    <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
      {[...Array(6)].map((_, i) => <FundCardSkeleton key={i} />)}
    </div>
  </div>
);
