'use client';

import { useRouter } from 'next/navigation';
import { AuthProviderWithRouter } from '@/contexts/AuthContext';

// 전 라우트에 공통으로 필요한 것만 루트에 둔다 = AuthContext.
// ThemeProvider·Socket·Toast·ClientLogBridge 등 인증 후 화면 전용 프로바이더는
// app/(app)/providers.js 로 내렸다. 그래야 진입 페이지(/, /register)가 vapor-ui
// JS/CSS 를 전혀 받지 않고(콜드로드 경량화), 인증 라우트에서 프로바이더가
// 이중 마운트되지 않는다(토스트/소켓 중복 제거).
export default function RootProviders({ children }) {
  const router = useRouter();

  return (
    <AuthProviderWithRouter router={router}>
      {children}
    </AuthProviderWithRouter>
  );
}
