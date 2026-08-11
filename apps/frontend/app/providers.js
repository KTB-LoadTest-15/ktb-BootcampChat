'use client';

import { ThemeProvider } from '@vapor-ui/core';
import dynamic from 'next/dynamic';
import { usePathname, useRouter } from 'next/navigation';
import { AuthProviderWithRouter } from '@/contexts/AuthContext';

const AppAuthenticatedShell = dynamic(
  () => import('@/components/AppAuthenticatedShell')
);

const PUBLIC_AUTH_PATHS = new Set(['/', '/register']);

export default function AppProviders({ children }) {
  const router = useRouter();
  const pathname = usePathname();
  const isPublicAuthPage = PUBLIC_AUTH_PATHS.has(pathname);

  return (
    <ThemeProvider defaultTheme="dark">
      <AuthProviderWithRouter router={router}>
        {isPublicAuthPage ? (
          <>{children}</>
        ) : (
          <AppAuthenticatedShell>{children}</AppAuthenticatedShell>
        )}
      </AuthProviderWithRouter>
    </ThemeProvider>
  );
}
