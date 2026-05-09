import React, { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Upload, FileText, Lock, AlertCircle } from 'lucide-react';

interface ModernCasDropzoneProps {
  onFileSelect: (file: File) => void;
  onPasswordChange: (password: string) => void;
  loading: boolean;
  error?: string | null;
}

const ModernCasDropzone: React.FC<ModernCasDropzoneProps> = ({ 
  onFileSelect, 
  onPasswordChange, 
  error 
}) => {
  const [isDragging, setIsDragging] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [password, setPassword] = useState('');

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  };

  const handleDragLeave = () => {
    setIsDragging(false);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      const file = e.dataTransfer.files[0];
      if (file.type === 'application/pdf') {
        setSelectedFile(file);
        onFileSelect(file);
      }
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      const file = e.target.files[0];
      setSelectedFile(file);
      onFileSelect(file);
    }
  };

  const clearFile = () => {
    setSelectedFile(null);
    onFileSelect(null as any);
  };

  return (
    <div className="w-full space-y-6">
      <motion.div
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        className={`relative group cursor-pointer transition-all duration-500 rounded-3xl border-2 border-dashed 
          ${isDragging ? 'border-accent bg-accent/5 scale-[1.02]' : 'border-white/10 bg-white/[0.02] hover:border-white/20'}
          ${selectedFile ? 'border-buy/30 bg-buy/5' : ''}
          ${error ? 'border-exit/30 bg-exit/5' : ''}`}
      >
        <input
          type="file"
          accept=".pdf"
          onChange={handleFileChange}
          className="absolute inset-0 w-full h-full opacity-0 cursor-pointer z-10"
        />

        <div className="p-12 flex flex-col items-center text-center space-y-4">
          <AnimatePresence mode="wait">
            {!selectedFile ? (
              <motion.div
                key="empty"
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -10 }}
                className="space-y-4"
              >
                <div className="w-16 h-16 rounded-2xl bg-white/5 flex items-center justify-center mx-auto group-hover:scale-110 group-hover:bg-accent/10 transition-all duration-500">
                  <Upload className={`h-8 w-8 ${isDragging ? 'text-accent' : 'text-muted'}`} />
                </div>
                <div className="space-y-1">
                  <p className="text-sm font-black uppercase tracking-widest text-primary">Drop CAS Statement</p>
                  <p className="text-[10px] font-bold text-muted uppercase tracking-[0.2em]">or click to browse filesystem</p>
                </div>
              </motion.div>
            ) : (
              <motion.div
                key="selected"
                initial={{ opacity: 0, scale: 0.9 }}
                animate={{ opacity: 1, scale: 1 }}
                className="space-y-4"
              >
                <div className="w-16 h-16 rounded-2xl bg-buy/10 flex items-center justify-center mx-auto">
                  <FileText className="h-8 w-8 text-buy" />
                </div>
                <div className="space-y-1">
                  <p className="text-sm font-black text-buy truncate max-w-[250px]">{selectedFile.name}</p>
                  <p className="text-[10px] font-bold text-muted uppercase tracking-widest">{(selectedFile.size / 1024 / 1024).toFixed(2)} MB • Ready to Inject</p>
                </div>
                <button 
                  onClick={(e) => { e.stopPropagation(); clearFile(); }}
                  className="text-[9px] font-black uppercase tracking-widest text-exit/60 hover:text-exit transition-colors"
                >
                  Change File
                </button>
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        {/* Dynamic Glow Effect */}
        <div className={`absolute inset-0 rounded-3xl transition-opacity duration-500 pointer-events-none 
          ${isDragging ? 'opacity-100 shadow-[0_0_50px_rgba(129,140,248,0.15)]' : 'opacity-0'}`} 
        />
      </motion.div>

      <AnimatePresence>
        {selectedFile && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }}
            className="space-y-4 overflow-hidden"
          >
            <div className="relative group">
              <div className="absolute left-4 top-1/2 -translate-y-1/2 text-muted group-focus-within:text-accent transition-colors">
                <Lock size={16} />
              </div>
              <input
                type="password"
                placeholder="PDF Unlock Key"
                value={password}
                onChange={(e) => { setPassword(e.target.value); onPasswordChange(e.target.value); }}
                className="w-full h-14 bg-white/[0.02] border border-white/5 rounded-2xl pl-12 pr-4 text-sm focus:outline-none focus:ring-1 focus:ring-accent/30 transition-all placeholder:text-muted/20"
              />
            </div>
            
            <div className="flex items-center gap-3 p-4 bg-white/[0.01] rounded-2xl border border-white/5">
              <div className="flex-shrink-0 w-8 h-8 rounded-lg bg-accent/5 flex items-center justify-center text-accent">
                <AlertCircle size={14} />
              </div>
              <p className="text-[9px] font-medium text-muted leading-relaxed uppercase tracking-wider">
                We decrypt your CAS locally in the browser/sidecar. Your password is never stored on our servers unless you enable "Remember Key".
              </p>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {error && (
        <motion.div
          initial={{ opacity: 0, x: -10 }}
          animate={{ opacity: 1, x: 0 }}
          className="flex items-center gap-3 p-4 bg-exit/5 border border-exit/10 rounded-2xl"
        >
          <AlertCircle className="text-exit h-4 w-4" />
          <p className="text-[10px] font-bold text-exit uppercase tracking-widest">{error}</p>
        </motion.div>
      )}
    </div>
  );
};

export default ModernCasDropzone;
