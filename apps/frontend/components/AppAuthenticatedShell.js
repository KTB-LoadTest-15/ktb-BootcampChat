'use client';

import ClientLogBridge from '@/components/ClientLogBridge';
import ToastContainer from '@/components/Toast';
import { useAuth } from '@/contexts/AuthContext';
import { SocketProvider } from '@/lib/socket/SocketProvider';

/**
 * 인증 후 화면에서만 필요한 공통 기능을 별도 청크로 분리한다.
 * 로그인·회원가입에서는 이 모듈을 받거나 초기화하지 않는다.
 */
export default function AppAuthenticatedShell({ children }) {
  const { user } = useAuth();

  return (
    <SocketProvider session={user}>
      <ClientLogBridge />
      {children}
      <ToastContainer />
    </SocketProvider>
  );
}
