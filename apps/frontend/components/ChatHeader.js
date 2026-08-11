'use client';

import React from 'react';
import Link from 'next/link';
import { HStack, Text } from '@vapor-ui/core';
import { useAuth } from '@/contexts/AuthContext';

// NavigationMenu(드롭다운 포지셔닝용 @floating-ui 포함, ~118KB parsed)를 쓰기엔
// 이 메뉴는 호버 서브메뉴 없이 링크 4개를 나열하는 정적 목록이라 과한 스펙이었다.
// 인증된 모든 페이지에서 매번 로드되는 헤더라, 평범한 링크로 바꿔 그 비용을 없앤다.
const NAV_ITEM_CLASSNAME =
  'bg-transparent border-none cursor-pointer rounded-lg px-2 py-1 text-foreground-hint-100 hover:text-foreground-normal-100 hover:bg-background-contrast-100 transition-colors';

const ChatHeader = () => {
  const { logout } = useAuth();

  const handleLogout = async () => {
    await logout();
  };

  return (
    <HStack
      $css={{
        justifyContent: 'space-between',
        alignItems: 'center',
        paddingInline: '$400',
        paddingBlock: '$300',
      }}
      className="bg-surface-200 backdrop-blur-sm sticky top-0 z-10"
    >
      {/* 왼쪽: 로고 */}
      <Link
        href="/chat"
        className="bg-transparent border-none cursor-pointer p-0"
        aria-label="채팅방 목록으로 이동"
      >
        <img
          src="/images/logo.png"
          alt="Chat App Logo"
          height={15}
          className="logo"
        />
      </Link>

      {/* 오른쪽: 네비게이션 메뉴 */}
      <nav aria-label="Chat Actions">
        <HStack $css={{ gap: '$100', alignItems: 'center' }}>
          <Link href="/chat" className={NAV_ITEM_CLASSNAME} data-testid="chat-list-link">
            <Text typography="body2">채팅방 목록</Text>
          </Link>
          <Link href="/chat/new" className={NAV_ITEM_CLASSNAME} data-testid="chat-new-link">
            <Text typography="body2">새 채팅방</Text>
          </Link>
          <Link href="/profile" className={NAV_ITEM_CLASSNAME} data-testid="profile-link">
            <Text typography="body2">프로필</Text>
          </Link>
          <button
            type="button"
            onClick={handleLogout}
            className={NAV_ITEM_CLASSNAME}
            data-testid="logout-link"
          >
            <Text typography="body2">로그아웃</Text>
          </button>
        </HStack>
      </nav>
    </HStack>
  );
};

export default ChatHeader;
