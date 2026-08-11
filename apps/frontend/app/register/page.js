'use client';

import React, { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/contexts/AuthContext';
import styles from '../auth-form.module.css';

// 회원가입 진입 페이지. 로그인 페이지와 동일한 이유로 네이티브 HTML 폼으로 재작성하고
// `isLoading` 게이트를 제거해, input 이 프리렌더 HTML 에 포함되어 FCP 시점부터
// fillable 하도록 한다. (부하 중 회원가입 타임아웃 완화)
export default function RegisterPage() {
  const router = useRouter();
  const { register: registerContext, isAuthenticated, isLoading } = useAuth();
  // 언컨트롤드 입력(하이드레이션 경계 안전) — 자세한 이유는 app/page.js 참고.
  const nameRef = useRef(null);
  const emailRef = useRef(null);
  const passwordRef = useRef(null);
  const confirmPasswordRef = useRef(null);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!isLoading && isAuthenticated) {
      router.replace('/chat');
    }
  }, [isAuthenticated, isLoading, router]);

  const handleSubmit = async (e) => {
    e.preventDefault();

    const name = nameRef.current?.value || '';
    const email = emailRef.current?.value || '';
    const password = passwordRef.current?.value || '';
    const confirmPassword = confirmPasswordRef.current?.value || '';

    if (password !== confirmPassword) {
      setError('비밀번호가 일치하지 않습니다.');
      return;
    }

    setLoading(true);
    setError(null);
    setSuccess(false);

    try {
      await registerContext({ name, email, password });

      setSuccess(true);
      setLoading(false);
      router.push('/');
    } catch (err) {
      setError(err.message || '회원가입 처리 중 오류가 발생했습니다.');
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
          <div className={styles.error} role="alert" data-testid="register-error-message">
            {error}
          </div>
        )}

        {success && (
          <div className={styles.success} role="status" data-testid="register-success-message">
            가입성공, 로그인 해 주세요.
          </div>
        )}

        <div className={styles.field}>
          <label className={styles.label} htmlFor="register-name">
            이름
          </label>
          <input
            id="register-name"
            ref={nameRef}
            className={styles.input}
            type="text"
            required
            disabled={loading}
            defaultValue=""
            placeholder="이름을 입력하세요"
            data-testid="register-name-input"
          />
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="register-email">
            이메일
          </label>
          <input
            id="register-email"
            ref={emailRef}
            className={styles.input}
            type="email"
            required
            disabled={loading}
            defaultValue=""
            placeholder="이메일을 입력하세요"
            data-testid="register-email-input"
          />
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="register-password">
            비밀번호
          </label>
          <input
            id="register-password"
            ref={passwordRef}
            className={styles.input}
            type="password"
            required
            disabled={loading}
            defaultValue=""
            placeholder="비밀번호를 입력하세요"
            pattern="(?=.*\d)(?=.*[a-z])(?=.*[A-Z])(?=.*[\W_]).{8,16}"
            data-testid="register-password-input"
          />
          <span className={styles.hint}>8~16자, 대소문자 영문, 숫자, 특수문자 포함</span>
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="register-password-confirm">
            비밀번호 확인
          </label>
          <input
            id="register-password-confirm"
            ref={confirmPasswordRef}
            className={styles.input}
            type="password"
            required
            disabled={loading}
            defaultValue=""
            placeholder="비밀번호를 다시 입력하세요"
            data-testid="register-password-confirm-input"
          />
        </div>

        <button
          type="submit"
          className={styles.submit}
          disabled={loading}
          data-testid="register-submit-button"
        >
          {loading ? '회원가입 중...' : '회원가입'}
        </button>

        <div className={styles.footer}>
          <span>이미 계정이 있으신가요?</span>
          <button
            type="button"
            className={styles.link}
            onClick={() => router.push('/')}
            disabled={loading}
          >
            로그인
          </button>
        </div>
      </form>
    </div>
  );
}
