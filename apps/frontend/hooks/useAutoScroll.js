import { useRef, useEffect, useCallback } from 'react';

/**
 * 채팅 메시지 자동 스크롤 훅
 * 
 * 특징:
 * - 내가 쓴 메시지: 무조건 최하단 스크롤
 * - 남이 쓴 메시지: 사용자가 하단 근처에 있을 때만 자동 스크롤
 * - 이전 메시지 로딩 시: 스크롤 위치 복원
 * 
 * @param {Array} messages - 메시지 배열
 * @param {string} currentUserId - 현재 사용자 ID
 * @param {boolean} isLoadingMessages - 이전 메시지 로딩 중 여부
 * @param {number} threshold - 자동 스크롤 임계값 (px, 기본 100)
 * @returns {Object} { containerRef, scrollToBottom, isNearBottom }
 */
export const useAutoScroll = (
  messages = [], 
  currentUserId = null, 
  isLoadingMessages = false,
  threshold = 100
) => {
  const containerRef = useRef(null);
  const isNearBottomRef = useRef(true);
  const previousMessagesLengthRef = useRef(0);
  const previousLastMessageIdRef = useRef(null);
  const isAutoScrollingRef = useRef(false);
  const scrollFrameRef = useRef(null);
  const scrollResetTimerRef = useRef(null);
  
  // 스크롤 복원을 위한 ref
  const previousScrollHeightRef = useRef(0);
  const previousScrollTopRef = useRef(0);
  const isRestoringRef = useRef(false);

  /**
   * 스크롤이 하단 근처에 있는지 확인
   */
  const checkIsNearBottom = useCallback(() => {
    const container = containerRef.current;
    if (!container) return true;

    const { scrollHeight, scrollTop, clientHeight } = container;
    const distanceFromBottom = scrollHeight - (scrollTop + clientHeight);

    return distanceFromBottom <= threshold;
  }, [threshold]);

  /**
   * 최하단으로 스크롤
   */
  const scrollToBottom = useCallback((behavior = 'smooth') => {
    if (scrollFrameRef.current !== null) {
      cancelAnimationFrame(scrollFrameRef.current);
    }

    scrollFrameRef.current = requestAnimationFrame(() => {
      scrollFrameRef.current = null;
      const container = containerRef.current;
      if (!container) return;

      isAutoScrollingRef.current = true;
      container.scrollTo({
        top: container.scrollHeight,
        behavior
      });

      if (scrollResetTimerRef.current !== null) {
        clearTimeout(scrollResetTimerRef.current);
      }
      scrollResetTimerRef.current = setTimeout(() => {
        scrollResetTimerRef.current = null;
        isAutoScrollingRef.current = false;
        isNearBottomRef.current = true;
      }, behavior === 'smooth' ? 300 : 0);
    });
  }, []);

  useEffect(() => () => {
    if (scrollFrameRef.current !== null) {
      cancelAnimationFrame(scrollFrameRef.current);
    }
    if (scrollResetTimerRef.current !== null) {
      clearTimeout(scrollResetTimerRef.current);
    }
  }, []);

  /**
   * 스크롤 이벤트 핸들러 - 사용자가 스크롤할 때 위치 추적
   */
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const handleScroll = () => {
      // 자동 스크롤 중이면 무시
      if (isAutoScrollingRef.current) return;

      isNearBottomRef.current = checkIsNearBottom();
    };

    container.addEventListener('scroll', handleScroll, { passive: true });

    return () => {
      container.removeEventListener('scroll', handleScroll);
    };
  }, [checkIsNearBottom]);

  /**
   * 이전 메시지 로딩 시작 시 스크롤 위치 저장
   */
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    if (isLoadingMessages && !isRestoringRef.current) {
      previousScrollHeightRef.current = container.scrollHeight;
      previousScrollTopRef.current = container.scrollTop;
      isRestoringRef.current = true;
    }
  }, [isLoadingMessages]);

  /**
   * 이전 메시지 로딩 완료 시 스크롤 위치 복원
   */
  useEffect(() => {
    const container = containerRef.current;
    if (!container || !isRestoringRef.current || isLoadingMessages) return;

    const newScrollHeight = container.scrollHeight;
    const heightDifference = newScrollHeight - previousScrollHeightRef.current;

    if (heightDifference > 0) {
      container.scrollTop = previousScrollTopRef.current + heightDifference;
    }

    isRestoringRef.current = false;
  }, [messages, isLoadingMessages]);

  /**
   * 메시지 추가 시 자동 스크롤 로직
   */
  useEffect(() => {
    // 스크롤 복원 중이면 자동 스크롤 안함
    if (isRestoringRef.current || isLoadingMessages) {
      return;
    }

    // 메시지가 추가되지 않았으면 무시
    if (messages.length === 0) {
      previousMessagesLengthRef.current = 0;
      previousLastMessageIdRef.current = null;
      return;
    }
    if (messages.length === previousMessagesLengthRef.current) {
      return;
    }

    // 이전보다 메시지가 줄었으면 (초기화 등) 무시
    if (messages.length < previousMessagesLengthRef.current) {
      previousMessagesLengthRef.current = messages.length;
      const latestMessage = messages[messages.length - 1];
      previousLastMessageIdRef.current = latestMessage?._id || latestMessage?.id || null;
      return;
    }

    const latestMessage = messages[messages.length - 1];
    const latestMessageId = latestMessage?._id || latestMessage?.id || null;
    const previousLength = previousMessagesLengthRef.current;

    // 과거 페이지가 앞에 추가된 경우 최신 메시지는 동일하다. 자동 하단 이동을 하지 않는다.
    if (
      previousLength > 0 &&
      previousLastMessageIdRef.current &&
      previousLastMessageIdRef.current === latestMessageId
    ) {
      previousMessagesLengthRef.current = messages.length;
      return;
    }

    // 새로 추가된 메시지들 확인
    const newMessages = messages.slice(previousLength);
    previousMessagesLengthRef.current = messages.length;
    previousLastMessageIdRef.current = latestMessageId;

    // 새 메시지가 없으면 무시
    if (newMessages.length === 0) return;

    if (!latestMessage) return;

    // 메시지 발신자 확인
    const senderId = latestMessage.sender?._id || latestMessage.sender?.id || latestMessage.sender;
    const isMyMessage = senderId === currentUserId;

    // 자동 스크롤 조건 확인
    if (isMyMessage) {
      // 내가 쓴 메시지 → 무조건 스크롤
      scrollToBottom(newMessages.length > 1 ? 'auto' : 'smooth');
    } else if (isNearBottomRef.current) {
      // 남이 쓴 메시지 + 하단 근처에 있음 → 자동 스크롤
      scrollToBottom(newMessages.length > 1 ? 'auto' : 'smooth');
    } else {
      // 남이 쓴 메시지 + 상단에 있음 → 스크롤 안함
    }
  }, [messages, currentUserId, scrollToBottom, isLoadingMessages]);

  /**
   * 초기 로드 시 최하단으로 스크롤
   */
  useEffect(() => {
    if (messages.length > 0 && previousMessagesLengthRef.current === 0) {
      // 초기 로드는 다음 프레임에 즉시 이동한다.
      scrollToBottom('auto');
    }
  }, [messages.length, scrollToBottom]);

  return {
    containerRef,
    scrollToBottom,
    isNearBottom: () => isNearBottomRef.current
  };
};

export default useAutoScroll;
