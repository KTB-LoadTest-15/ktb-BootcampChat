import { useState } from 'react';

export const CONNECTION_STATUS = {
  CHECKING: 'checking',
  CONNECTING: 'connecting',
  CONNECTED: 'connected',
  DISCONNECTED: 'disconnected',
  ERROR: 'error',
};

export const useServerConnection = () => {
  const [connectionStatus, setConnectionStatus] = useState(CONNECTION_STATUS.CHECKING);

  return {
    connectionStatus,
    setConnectionStatus,
  };
};

export default useServerConnection;
