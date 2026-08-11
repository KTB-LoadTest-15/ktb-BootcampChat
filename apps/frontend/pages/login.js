import { useEffect } from 'react';
import LoginPage from './index';

/**
 * /login 호환 라우트.
 *
 * 로그인 화면은 Pages Router의 `/`가 소유한다. 기존처럼 router.replace('/')를
 * 실행하면 회원가입의 /login 이동이나 Playwright의 page.goto('/login')과 새로운
 * 내비게이션이 경쟁할 수 있으므로, 같은 로그인 화면을 그대로 렌더링한 뒤 주소만
 * History API로 정규화한다.
 */
const LoginCompatibilityPage = (props) => {
  useEffect(() => {
    const queryString = window.location.search;
    const canonicalUrl = queryString ? `/${queryString}` : '/';

    window.history.replaceState(window.history.state, '', canonicalUrl);
  }, []);

  return <LoginPage {...props} />;
};

export default LoginCompatibilityPage;
