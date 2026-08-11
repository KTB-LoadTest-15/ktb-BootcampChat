'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

// App Router 세그먼트 에러 바운더리. 루트 레이아웃에서 vapor ThemeProvider/CSS 를
// 제거했으므로 네이티브 마크업 + 인라인 스타일로 자립한다. 404 는 not-found.js 담당.
const page = {
  minHeight: '100vh',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  gap: '24px',
  padding: '32px',
  textAlign: 'center',
  backgroundColor: '#16171a',
  color: '#e8e9ea',
};
const primaryButton = {
  padding: '10px 18px',
  fontSize: '15px',
  fontWeight: 600,
  border: 'none',
  borderRadius: '8px',
  backgroundColor: '#2f6feb',
  color: '#ffffff',
  cursor: 'pointer',
};
const ghostButton = {
  padding: '10px 18px',
  fontSize: '15px',
  fontWeight: 600,
  border: '1px solid #34363c',
  borderRadius: '8px',
  backgroundColor: 'transparent',
  color: '#e8e9ea',
  cursor: 'pointer',
};

export default function Error({ error, reset }) {
  const router = useRouter();

  useEffect(() => {
    console.error('App error boundary:', error);
  }, [error]);

  return (
    <div style={page}>
      <img
        src="/404-dark.svg"
        alt=""
        style={{ width: '280px', maxWidth: '80%', height: 'auto' }}
      />
      <h1 style={{ fontSize: '20px', fontWeight: 700, margin: 0 }}>
        문제가 발생했어요
      </h1>
      <p style={{ fontSize: '14px', color: '#9aa0a6', margin: 0, lineHeight: 1.5 }}>
        페이지를 표시하는 중 오류가 발생했습니다.
        <br />
        잠시 후 다시 시도해주세요.
      </p>
      <div style={{ display: 'flex', gap: '12px' }}>
        <button type="button" style={primaryButton} onClick={() => reset()}>
          다시 시도
        </button>
        <button type="button" style={ghostButton} onClick={() => router.push('/')}>
          홈으로 돌아가기
        </button>
      </div>
    </div>
  );
}
