import { useState, useCallback } from 'react';
import axiosInstance from '@/services/axios';
import { HEALTH_TIMEOUT_MS } from '@/lib/api/client';

export const CONNECTION_STATUS = {
  CHECKING: 'checking',
  CONNECTING: 'connecting',
  CONNECTED: 'connected',
  DISCONNECTED: 'disconnected',
  ERROR: 'error',
};

export const useServerConnection = () => {
  const [connectionStatus, setConnectionStatus] = useState(CONNECTION_STATUS.CHECKING);

  const attemptConnection = useCallback(async () => {
    try {
      setConnectionStatus(CONNECTION_STATUS.CONNECTING);

      const response = await axiosInstance.get('/api/health', {
        timeout: HEALTH_TIMEOUT_MS,
      });

      if (response?.status !== 200 || response?.data?.status !== 'ok') {
        throw new Error('SERVER_UNREACHABLE');
      }

      setConnectionStatus(CONNECTION_STATUS.CONNECTED);
      return true;
    } catch (error) {
      setConnectionStatus(CONNECTION_STATUS.ERROR);

      if (error.code === 'AUTH_EXPIRED' || error.message === 'AUTH_EXPIRED') {
        throw error;
      }

      const connectionError = new Error('SERVER_UNREACHABLE');
      connectionError.originalError = error;
      throw connectionError;
    }
  }, []);

  return {
    connectionStatus,
    setConnectionStatus,
    attemptConnection,
  };
};

export default useServerConnection;
