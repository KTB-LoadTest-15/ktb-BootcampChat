// vapor-ui 컴포넌트 스타일(~324KB, 렌더블로킹)은 이 그룹의 화면들만 필요로 한다.
// 루트 레이아웃에서 이 그룹으로 내려, 진입 페이지(/, /register)의 콜드로드에서
// 이 CSS 를 제외한다.
import '@vapor-ui/core/styles.css';
import AppProviders from './providers';

export default function AppGroupLayout({ children }) {
  return <AppProviders>{children}</AppProviders>;
}
