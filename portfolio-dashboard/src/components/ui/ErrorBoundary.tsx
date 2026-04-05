import { Component, type ErrorInfo, type ReactNode } from "react";
import { AlertTriangle, RefreshCcw } from "lucide-react";

interface Props {
  children?: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

class ErrorBoundary extends Component<Props, State> {
  public override state: State = {
    hasError: false,
    error: null
  };

  public static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  public override componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error("Uncaught error:", error, errorInfo);
  }

  public override render() {
    if (this.state.hasError) {
      return (
        <div className="min-h-[400px] flex flex-col items-center justify-center p-8 bg-zinc-900/20 border border-rose-500/20 rounded-[3rem] text-center">
          <div className="w-16 h-16 bg-rose-500/10 rounded-full flex items-center justify-center mb-6">
            <AlertTriangle className="text-rose-500" size={32} />
          </div>
          <h2 className="text-xl font-black text-white uppercase tracking-tighter mb-2">System Kernel Panic</h2>
          <p className="text-zinc-500 text-xs font-bold uppercase tracking-widest max-w-md mb-8">
            An unexpected architectural failure occurred in the UI layer. 
            Detailed diagnostics: {this.state.error?.message || "Unknown Error"}
          </p>
          <button
            onClick={() => window.location.reload()}
            className="flex items-center gap-2 px-6 py-3 bg-white text-black rounded-full font-black text-[10px] uppercase tracking-widest hover:bg-zinc-200 transition-all shadow-xl"
          >
            <RefreshCcw size={14} /> Reboot Interface
          </button>
        </div>
      );
    }

    return this.props.children;
  }
}

export default ErrorBoundary;
