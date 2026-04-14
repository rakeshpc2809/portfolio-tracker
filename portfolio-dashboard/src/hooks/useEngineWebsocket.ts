import { useState, useEffect } from 'react';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { useQueryClient } from '@tanstack/react-query';

export interface EngineProgress {
  step: number;
  message: string;
  total: number;
}

export function useEngineWebsocket() {
  const [progress, setProgress] = useState<EngineProgress | null>(null);
  const queryClient = useQueryClient();

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS('/api/ws'),
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        client.subscribe('/topic/engine-progress', (msg) => {
          const data = JSON.parse(msg.body) as EngineProgress;
          setProgress(data);

          // Invalidate cache when engine finishes (Step 7)
          if (data.step === 7) {
            queryClient.invalidateQueries({ queryKey: ['portfolio'] });
            queryClient.invalidateQueries({ queryKey: ['performance'] });
            queryClient.invalidateQueries({ queryKey: ['transactions'] });
            queryClient.invalidateQueries({ queryKey: ['correlation'] });
          }
        });
      },
      onDisconnect: () => {
        setProgress(null); // Clear stale progress on disconnect
      },
      // debug: (str) => console.log('STOMP: ' + str),
    });

    client.activate();
    return () => { client.deactivate(); };
  }, [queryClient]);

  return progress;
}
