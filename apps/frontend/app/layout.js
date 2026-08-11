// NOTE: vapor-ui 컴포넌트 CSS(styles.css, ~324KB)는 루트에서 제거하고
// app/(app)/layout.js 로 옮겼다. 진입 페이지(/, /register)는 네이티브 폼으로
// 재작성돼 이 CSS 가 필요 없고, 렌더블로킹 CSS 를 줄여 콜드로드 FCP 를 낮춘다.
// globals.css(tailwind 유틸리티 + 디자인 토큰)는 전 라우트에 필요하므로 유지한다.
import '../styles/globals.css';
import RootProviders from './providers';

export const metadata = {
  title: 'KTB Chat',
};

export default function RootLayout({ children }) {
  return (
    <html lang="ko">
      <body>
        <RootProviders>{children}</RootProviders>
      </body>
    </html>
  );
}
