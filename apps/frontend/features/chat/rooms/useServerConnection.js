import { useState, useCallback } from 'react';
import axiosInstance from '@/services/axios';
import { HEALTH_TIMEOUT_MS } from '@/lib/api/client';

export const API_STATUS = {
  CHECKING: 'checking',
  HEALTHY: 'healthy',
  UNHEALTHY: 'unhealthy',
  ERROR: 'error',
};

// Transitional alias for older tests/usages that still import CONNECTION_STATUS.
export const CONNECTION_STATUS = API_STATUS;

export const useServerConnection = () => {
  const [apiStatus, setApiStatus] = useState(API_STATUS.CHECKING);

  const attemptConnection = useCallback(async () => {
    if (apiStatus === API_STATUS.HEALTHY) {
      return true;
    }

    try {
      setApiStatus(API_STATUS.CHECKING);

      const response = await axiosInstance.get('/api/health', {
        timeout: HEALTH_TIMEOUT_MS,
        maxRetries: 0,
      });

      if (response?.status !== 200 || response?.data?.status !== 'ok') {
        setApiStatus(API_STATUS.UNHEALTHY);
        throw new Error('SERVER_UNHEALTHY');
      }

      setApiStatus(API_STATUS.HEALTHY);
      return true;
    } catch (error) {
      if (error.code === 'AUTH_EXPIRED' || error.message === 'AUTH_EXPIRED') {
        setApiStatus(API_STATUS.ERROR);
        throw error;
      }

      if (error.message === 'SERVER_UNHEALTHY') {
        throw error;
      }

      setApiStatus(API_STATUS.ERROR);

      const connectionError = new Error('SERVER_UNREACHABLE');
      connectionError.originalError = error;
      throw connectionError;
    }
  }, [apiStatus]);

  return {
    apiStatus,
    setApiStatus,
    attemptConnection,
  };
};

export default useServerConnection;
