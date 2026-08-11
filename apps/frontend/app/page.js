'use client';

import React, { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { ErrorCircleIcon } from '@vapor-ui/icons';
import { useAuth } from '@/contexts/AuthContext';
import {
    Box,
    Button,
    Callout,
    Field,
    Form,
    HStack,
    Text,
    TextInput,
    VStack,
} from '@vapor-ui/core';

const LoadingState = () => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      height: '100vh',
      backgroundColor: 'var(--vapor-color-background)',
      color: 'var(--vapor-color-text-primary)',
    }}
  >
    <div>Loading...</div>
  </div>
);

export default function LoginPage() {
  const router = useRouter();
  const { login, isAuthenticated, isLoading } = useAuth();
  const [formData, setFormData] = useState({
    email: '',
    password: ''
  });
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  // 이미 로그인한 사용자는 /chat 으로 보낸다 (Pages Router 의 withoutAuth 가드 대체).
  useEffect(() => {
    if (!isLoading && isAuthenticated) {
      router.replace('/chat');
    }
  }, [isAuthenticated, isLoading, router]);

  const handleSubmit = async (e) => {
    e.preventDefault();

    setLoading(true);
    setError(null);

    try {
      const loginCredentials = {
        email: formData.email.trim(),
        password: formData.password
      };

      // AuthContext의 login 메서드 사용 (API 호출 + 상태 저장)
      await login(loginCredentials);

      // App Router 에는 router.query 가 없으므로 현재 URL 의 쿼리에서 redirect 를 읽는다.
      const redirectUrl =
        new URLSearchParams(window.location.search).get('redirect') || '/chat';
      router.push(redirectUrl);
    } catch (err) {
      setError(err.message || '로그인 처리 중 오류가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  if (isLoading || isAuthenticated) {
    return <LoadingState />;
  }

  return (
    <div className="min-h-screen flex items-center justify-center p-(--vapor-space-300) bg-(--vapor-color-background)">
      <VStack
        $css={{
          gap: '$250',
          width: '400px',
          padding: '$300',
          borderRadius: '$300',
          border: '1px solid var(--vapor-color-border-normal)',
        }}
        render={<Form onSubmit={handleSubmit} />}
      >
        <div className="text-center mb-4">
          <img src="images/logo-h.png" className="w-1/2 mx-auto" alt="KTB Chat 로고" />
        </div>

        {error && (
          <Callout.Root colorPalette="warning" data-testid="login-error-message">
            <Callout.Icon>
              <ErrorCircleIcon />
            </Callout.Icon>
            {error}
          </Callout.Root>
        )}

        <VStack $css={{ gap: '$400' }}>
          <VStack $css={{ gap: '$200' }}>
            <Field.Root>
              <Box
                render={<Field.Label />}
                $css={{ flexDirection: 'column' }}
                style={{ fontSize: '14px', fontWeight: '500', marginBottom: '8px' }}
              >
                이메일
                <TextInput
                  id="login-email"
                  size="lg"
                  type="email"
                  required
                  disabled={loading}
                  value={formData.email}
                  onValueChange={(value) => setFormData(prev => ({ ...prev, email: value }))}
                  placeholder="이메일을 입력하세요"
                  data-testid="login-email-input"
                />
              </Box>
              <Field.Error match="valueMissing">이메일을 입력해주세요.</Field.Error>
              <Field.Error match="typeMismatch">유효한 이메일 형식이 아닙니다.</Field.Error>
            </Field.Root>

            <Field.Root>
              <Box
                render={<Field.Label />}
                $css={{ flexDirection: 'column' }}
                style={{ fontSize: '14px', fontWeight: '500', marginBottom: '8px' }}
              >
                비밀번호
                <TextInput
                  id="login-password"
                  size="lg"
                  type="password"
                  required
                  disabled={loading}
                  value={formData.password}
                  onValueChange={(value) => setFormData(prev => ({ ...prev, password: value }))}
                  placeholder="비밀번호를 입력하세요"
                  data-testid="login-password-input"
                />
              </Box>
              <Field.Error match="valueMissing">비밀번호를 입력해주세요.</Field.Error>
            </Field.Root>
          </VStack>

          <Button
            type="submit"
            size="lg"
            disabled={loading}
            data-testid="login-submit-button"
          >
            {loading ? '로그인 중...' : '로그인'}
          </Button>
        </VStack>

        <HStack $css={{ justifyContent: 'center' }}>
          <Text typography="body2">계정이 없으신가요?</Text>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => router.push('/register')}
            disabled={loading}
          >
            회원가입
          </Button>
        </HStack>
      </VStack>
    </div>
  );
}
