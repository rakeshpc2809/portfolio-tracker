export default function Navigation({ activeTab, setActiveTab }: { activeTab: string, setActiveTab: (tab: string) => void }) {
  const tabs = [
    { id: 'overview', label: 'Overview' },
    { id: 'deviation', label: 'Rebalance Drift' },
    { id: 'holdings', label: 'Holdings Matrix' },
    { id: 'tactical', label: 'Action Required' } // The new tab!
  ];

  return (
    <div className="flex justify-center">
        <div className="flex bg-zinc-900/80 p-1.5 rounded-2xl border border-zinc-800 overflow-x-auto">
            {tabs.map((t) => (
                <button 
                  key={t.id} 
                  onClick={() => setActiveTab(t.id)}
                  className={`px-6 py-2.5 rounded-xl text-[10px] font-black uppercase tracking-widest transition-all duration-300 whitespace-nowrap ${
                    activeTab === t.id ? 'bg-blue-600 text-white shadow-lg' : 'text-zinc-500 hover:text-zinc-300'
                  }`}
                >
                {t.label}
                </button>
            ))}
        </div>
    </div>
  );
}