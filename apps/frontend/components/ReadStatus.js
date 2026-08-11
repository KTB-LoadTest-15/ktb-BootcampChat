import React, { useMemo, useEffect, useState, useCallback, useRef } from 'react';
import { ConfirmOutlineIcon } from '@vapor-ui/icons';
import { Text, HStack } from '@vapor-ui/core';

const cursorFor = (cursors, participant) => {
  if (!cursors) return -1;
  const byId = cursors[participant?._id];
  if (byId !== undefined) return byId;
  const byAltId = cursors[participant?.id];
  return byAltId !== undefined ? byAltId : -1;
};

const ReadStatus = ({
  messageType = 'text',
  participants = [],
  cursors = {},
  messageTimestamp = 0,
  className = '',
  messageId = null,
  messageRef = null, // 메시지 요소의 ref 추가
  currentUserId = null, // 현재 사용자 ID 추가
  onMessageRead = () => false
}) => {
  const [hasMarkedAsRead, setHasMarkedAsRead] = useState(false);
  const statusRef = useRef(null);
  const observerRef = useRef(null);

  // 읽지 않은 참여자 수: 커서(cursor[userId] < 메시지 timestamp)에서 파생.
  const unreadCount = useMemo(() => {
    if (messageType === 'system') return 0;
    return participants.reduce(
      (count, participant) => (cursorFor(cursors, participant) < messageTimestamp ? count + 1 : count),
      0
    );
  }, [participants, cursors, messageTimestamp, messageType]);

  // 내가 이미 이 메시지를 읽었는지(내 커서가 메시지 timestamp 이상).
  const alreadyReadByMe = (cursors?.[currentUserId] ?? -1) >= messageTimestamp;

  // 메시지를 읽음으로 표시(뷰포트 진입 시 마지막 읽은 timestamp를 배칭 송신).
  const markMessageAsRead = useCallback(() => {
    if (!messageId || !currentUserId || hasMarkedAsRead || messageType === 'system') {
      return;
    }
    try {
      if (onMessageRead(messageTimestamp)) {
        setHasMarkedAsRead(true);
      }
    } catch (error) {
      console.error('Error marking message as read:', error);
    }
  }, [messageId, currentUserId, hasMarkedAsRead, messageType, messageTimestamp, onMessageRead]);

  // Intersection Observer 설정
  useEffect(() => {
    if (!messageRef?.current || !currentUserId || hasMarkedAsRead || messageType === 'system') {
      return;
    }

    // 이미 내 커서가 이 메시지를 덮으면 재전송 불필요.
    if (alreadyReadByMe) {
      setHasMarkedAsRead(true);
      return;
    }

    const observerOptions = {
      root: null,
      rootMargin: '0px',
      threshold: 0.5 // 메시지의 50%가 보여야 읽음으로 처리
    };

    const handleIntersect = (entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting && !hasMarkedAsRead) {
          markMessageAsRead();
        }
      });
    };

    observerRef.current = new IntersectionObserver(handleIntersect, observerOptions);
    observerRef.current.observe(messageRef.current);

    return () => {
      if (observerRef.current) {
        observerRef.current.disconnect();
      }
    };
  }, [messageRef, currentUserId, hasMarkedAsRead, messageType, alreadyReadByMe, markMessageAsRead]);

  // 시스템 메시지는 읽음 상태 표시 안 함
  if (messageType === 'system') {
    return null;
  }

  // 모두 읽은 경우
  if (unreadCount === 0) {
    return (
      <HStack
        className={className}
        ref={statusRef}
        $css={{ gap: '$050', alignItems: 'center' }}
        role="status"
        aria-label="모든 참여자가 메시지를 읽었습니다"
        data-testid="read-status-all-read"
      >
        <HStack $css={{ alignItems: 'center' }}>
          <ConfirmOutlineIcon size={12} className='text-v-success-100' />
          <ConfirmOutlineIcon size={12} className='-ml-1.5 text-v-success-100' />
        </HStack>
        <Text typography="subtitle2" className="text-v-hint-200">모두 읽음</Text>
      </HStack>
    );
  }

  // 읽지 않은 사람이 있는 경우
  return (
    <HStack
      className={className}
      ref={statusRef}
      $css={{ gap: '$050', alignItems: 'center' }}
      role="status"
      aria-label={`${unreadCount}명이 메시지를 읽지 않았습니다`}
      data-testid="read-status-unread"
    >
      <ConfirmOutlineIcon size={12} className="text-v-hint-200" />
      {unreadCount > 0 && (
        <Text typography="subtitle2" className="text-v-hint-200">
          {unreadCount}명 안 읽음
        </Text>
      )}
    </HStack>
  );
};

export default ReadStatus;
