'use client';

import { ThemeProvider } from '@vapor-ui/core';
import ClientLogBridge from '@/components/ClientLogBridge';
import ToastContainer from '@/components/Toast';
import { useAuth } from '@/contexts/AuthContext';
import { SocketProvider } from '@/lib/socket/SocketProvider';

const AuthenticatedSocketProvider = ({ children }) => {
  const { user } = useAuth();

  return (
    <SocketProvider session={user}>
      {children}
    </SocketProvider>
  );
};

// 인증 이후 화면((app) 그룹)에서만 마운트되는 무거운 프로바이더 묶음.
// AuthProvider 는 루트(app/providers.js)에 있으므로 useAuth 는 여기서도 동작한다.
export default function AppProviders({ children }) {
  return (
    <ThemeProvider defaultTheme="dark">
      <AuthenticatedSocketProvider>
        <ClientLogBridge />
        {children}
        <ToastContainer />
      </AuthenticatedSocketProvider>
    </ThemeProvider>
  );
}
