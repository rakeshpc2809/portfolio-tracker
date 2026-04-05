export const Skeleton = ({ className }: { className?: string }) => {
  return (
    <div className={`animate-pulse bg-zinc-800/50 rounded-lg ${className}`} />
  );
};

export const OverviewSkeleton = () => {
  return (
    <div className="space-y-8 pb-32">
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {[1, 2, 3, 4].map((i) => (
          <Skeleton key={i} className="h-32 rounded-[2rem]" />
        ))}
      </div>
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
        <Skeleton className="h-96 rounded-[3rem]" />
        <Skeleton className="lg:col-span-2 h-96 rounded-[3rem]" />
      </div>
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-8 pt-8 border-t border-zinc-800">
        <div className="space-y-3">
          {[1, 2, 3, 4, 5].map((i) => (
            <Skeleton key={i} className="h-20 rounded-2xl" />
          ))}
        </div>
        <Skeleton className="lg:col-span-2 h-[500px] rounded-[3rem]" />
      </div>
    </div>
  );
};
