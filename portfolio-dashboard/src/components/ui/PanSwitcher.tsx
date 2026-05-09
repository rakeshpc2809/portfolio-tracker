import { useState, useEffect } from 'react';

import { Users, ChevronDown, UserPlus, X, Check } from 'lucide-react';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';

interface PanSwitcherProps {
  currentPan: string;
  onSwitch: (pan: string) => void;
  onLogout: () => void;
}

export default function PanSwitcher({ currentPan, onSwitch, onLogout }: PanSwitcherProps) {
  const [savedPans, setSavedPans] = useState<string[]>([]);

  useEffect(() => {
    const pans = JSON.parse(localStorage.getItem('portfolio_pans') || '[]');
    if (!pans.includes(currentPan) && currentPan !== 'NEW_USER' && currentPan !== 'SETUP') {
      const updated = [...pans, currentPan];
      localStorage.setItem('portfolio_pans', JSON.stringify(updated));
      setSavedPans(updated);
    } else {
      setSavedPans(pans);
    }
  }, [currentPan]);

  const removePan = (pan: string) => {
    const updated = savedPans.filter(p => p !== pan);
    localStorage.setItem('portfolio_pans', JSON.stringify(updated));
    setSavedPans(updated);
    if (pan === currentPan) {
      onLogout();
    }
  };

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <button className="flex items-center gap-3 px-4 py-2 bg-white/[0.03] border border-border rounded-xl hover:bg-white/[0.06] transition-all group outline-none">
          <div className="w-6 h-6 rounded-lg bg-accent/20 flex items-center justify-center text-accent">
            <Users size={14} />
          </div>
          <div className="text-left hidden sm:block">
            <p className="text-[8px] font-black uppercase tracking-widest text-muted opacity-60">Account</p>
            <p className="text-[11px] font-black text-primary tracking-wider">{currentPan}</p>
          </div>
          <ChevronDown size={14} className="text-muted group-data-[state=open]:rotate-180 transition-transform" />
        </button>
      </DropdownMenu.Trigger>

      <DropdownMenu.Portal>
        <DropdownMenu.Content 
          align="end" 
          sideOffset={8}
          className="w-64 bg-surface/95 backdrop-blur-2xl border border-white/10 rounded-2xl p-2 shadow-2xl z-[100] animate-in fade-in zoom-in-95 duration-200"
        >
          <div className="px-3 py-2 mb-2">
            <p className="text-[10px] font-black uppercase tracking-[0.2em] text-muted opacity-40">Switch Profile</p>
          </div>

          {savedPans.map((pan) => (
            <DropdownMenu.Item 
              key={pan}
              onSelect={() => onSwitch(pan)}
              className="flex items-center justify-between px-3 py-2.5 rounded-xl hover:bg-white/5 outline-none cursor-pointer group transition-colors"
            >
              <div className="flex items-center gap-3">
                <div className={`w-2 h-2 rounded-full ${pan === currentPan ? 'bg-buy shadow-[0_0_8px_rgba(166,227,161,0.6)]' : 'bg-muted/20'}`} />
                <span className={`text-xs font-black tracking-wider ${pan === currentPan ? 'text-primary' : 'text-muted group-hover:text-secondary'}`}>
                  {pan}
                </span>
              </div>
              {pan === currentPan ? (
                <Check size={14} className="text-buy" />
              ) : (
                <button 
                  onClick={(e) => {
                    e.stopPropagation();
                    removePan(pan);
                  }}
                  className="opacity-0 group-hover:opacity-100 p-1 hover:text-exit transition-all"
                >
                  <X size={12} />
                </button>
              )}
            </DropdownMenu.Item>
          ))}

          <DropdownMenu.Separator className="h-px bg-white/5 my-2" />
          
          <DropdownMenu.Item 
            onSelect={onLogout}
            className="flex items-center gap-3 px-3 py-2.5 rounded-xl hover:bg-exit/10 text-muted hover:text-exit outline-none cursor-pointer transition-all"
          >
            <UserPlus size={14} />
            <span className="text-[10px] font-black uppercase tracking-widest">Add New Account</span>
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
