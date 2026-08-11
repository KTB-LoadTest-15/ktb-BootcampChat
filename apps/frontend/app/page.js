'use client';

import React, { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import styles from './auth-form.module.css';

// 로그인 진입 페이지.
//
// 부하테스트에서 이 페이지의 로그인 input 이 30s 안에 fillable 이 되지 못해
// 실패가 몰렸다. 원인은 (1) vapor-ui 컴포넌트로 조립된 무거운 폼의 하이드레이션
// 지연과 (2) `isLoading` 게이트로 인해 프리렌더 HTML 에 폼이 아닌 "Loading..."
// 만 담긴 것이었다. 그래서 이 페이지는
//   - 네이티브 HTML 폼으로 재작성해 하이드레이션 비용을 최소화하고
//   - 인증 상태와 무관하게 폼을 즉시 렌더(정적 HTML 에 input 포함)해
//     input 이 FCP 시점부터 DOM 에 존재·fillable 하게 한다.
// 이미 로그인한 사용자는 폼을 잠깐 보이며 effect 에서 /chat 으로 보낸다.
export default function LoginPage() {
  const router = useRouter();
  const { login, isAuthenticated, isLoading } = useAuth();
  // 언컨트롤드 입력: 폼이 정적 HTML 로 먼저 뜨므로, 부하 중 Playwright/사용자가
  // 하이드레이션 완료 전에 값을 채울 수 있다. 컨트롤드(value+onChange)였다면
  // 하이드레이션 전 입력이 state 에 반영되지 않아 제출 시 빈 값이 읽힌다.
  // ref 로 제출 시점의 실제 DOM 값을 읽어 이 경계 문제를 피한다.
  // (name 속성은 일부러 두지 않는다 — 하이드레이션 전 native 제출이 일어나도
  //  자격증명이 GET 쿼리스트링으로 직렬화되지 않게.)
  const emailRef = useRef(null);
  const passwordRef = useRef(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

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
      await login({
        email: (emailRef.current?.value || '').trim(),
        password: passwordRef.current?.value || '',
      });

      const redirectUrl =
        new URLSearchParams(window.location.search).get('redirect') || '/chat';
      router.push(redirectUrl);
    } catch (err) {
      setError(err.message || '로그인 처리 중 오류가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className={styles.page}>
      <form className={styles.card} onSubmit={handleSubmit}>
        <div className={styles.logo}>
          <img src="images/logo-h.png" alt="KTB Chat 로고" />
        </div>

        {error && (
          <div className={styles.error} role="alert" data-testid="login-error-message">
            {error}
          </div>
        )}

        <div className={styles.field}>
          <label className={styles.label} htmlFor="login-email">
            이메일
          </label>
          <input
            id="login-email"
            ref={emailRef}
            className={styles.input}
            type="email"
            required
            disabled={loading}
            defaultValue=""
            placeholder="이메일을 입력하세요"
            data-testid="login-email-input"
          />
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="login-password">
            비밀번호
          </label>
          <input
            id="login-password"
            ref={passwordRef}
            className={styles.input}
            type="password"
            required
            disabled={loading}
            defaultValue=""
            placeholder="비밀번호를 입력하세요"
            data-testid="login-password-input"
          />
        </div>

        <button
          type="submit"
          className={styles.submit}
          disabled={loading}
          data-testid="login-submit-button"
        >
          {loading ? '로그인 중...' : '로그인'}
        </button>

        <div className={styles.footer}>
          <span>계정이 없으신가요?</span>
          <button
            type="button"
            className={styles.link}
            onClick={() => router.push('/register')}
            disabled={loading}
          >
            회원가입
          </button>
        </div>
      </form>
    </div>
  );
}
