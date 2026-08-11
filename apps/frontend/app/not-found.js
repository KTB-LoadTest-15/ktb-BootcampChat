'use client';

import { useRouter } from 'next/navigation';

// 루트 레이아웃에서 vapor ThemeProvider/CSS 를 제거했으므로 이 페이지는
// vapor 컴포넌트 대신 네이티브 마크업 + 인라인 스타일로 자립한다.
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
const button = {
  padding: '10px 18px',
  fontSize: '15px',
  fontWeight: 600,
  border: 'none',
  borderRadius: '8px',
  backgroundColor: '#2f6feb',
  color: '#ffffff',
  cursor: 'pointer',
};

export default function NotFound() {
  const router = useRouter();

  return (
    <div style={page}>
      <img
        src="/404-dark.svg"
        alt=""
        style={{ width: '280px', maxWidth: '80%', height: 'auto' }}
      />
      <h1 style={{ fontSize: '20px', fontWeight: 700, margin: 0 }}>
        페이지를 찾을 수 없어요
      </h1>
      <p style={{ fontSize: '14px', color: '#9aa0a6', margin: 0, lineHeight: 1.5 }}>
        요청하신 페이지를 찾을 수 없습니다.
        <br />
        주소를 다시 확인해주세요.
      </p>
      <button type="button" style={button} onClick={() => router.push('/')}>
        홈으로 돌아가기
      </button>
    </div>
  );
}
