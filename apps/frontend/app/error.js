'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { Button, HStack, Text, VStack } from '@vapor-ui/core';

// App Router 세그먼트 에러 바운더리. 렌더 중 던져진 런타임 에러를 잡아
// 커스텀 화면을 보여준다(없으면 Next 기본 에러 화면). Pages Router 의 _error.js 를
// App Router 방식으로 대체하는 쪽(런타임 에러). 404 는 not-found.js 가 담당한다.
export default function Error({ error, reset }) {
  const router = useRouter();

  useEffect(() => {
    console.error('App error boundary:', error);
  }, [error]);

  return (
    <VStack
      $css={{
        minHeight: '100vh',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 'var(--vapor-space-300)',
        padding: 'var(--vapor-space-400)',
        textAlign: 'center',
        backgroundColor: 'var(--vapor-color-background)',
      }}
    >
      <img
        src="/404-dark.svg"
        alt=""
        style={{ width: '280px', maxWidth: '80%', height: 'auto' }}
      />
      <Text typography="heading3" foreground="normal-100">
        문제가 발생했어요
      </Text>
      <Text typography="body2" foreground="normal-200">
        페이지를 표시하는 중 오류가 발생했습니다.
        <br />
        잠시 후 다시 시도해주세요.
      </Text>
      <HStack $css={{ gap: 'var(--vapor-space-200)' }}>
        <Button colorPalette="primary" onClick={() => reset()}>
          다시 시도
        </Button>
        <Button variant="ghost" onClick={() => router.push('/')}>
          홈으로 돌아가기
        </Button>
      </HStack>
    </VStack>
  );
}
