import React, { useCallback, useEffect, useMemo } from 'react';
import { Spinner, Text, VStack } from '@vapor-ui/core';
import SystemMessage from './SystemMessage';
import FileMessage from './FileMessage';
import UserMessage from './UserMessage';
import { useInfiniteScroll } from '../hooks/useInfiniteScroll';
import { useAutoScroll } from '../hooks/useAutoScroll';
import { useVirtualMessageList } from '../hooks/useVirtualMessageList';
import { useReadReceiptBatch } from '../features/chat/room/useReadReceiptBatch';

const EMPTY_PARTICIPANTS = Object.freeze([]);

const LoadingIndicator = React.memo(() => (
  <div className="loading-messages">
    <Spinner size="md" colorPalette="primary" aria-label="이전 메시지 로딩 중" />
    <span className="text-secondary text-sm">이전 메시지를 불러오는 중...</span>
  </div>
));
LoadingIndicator.displayName = 'LoadingIndicator';

const MessageHistoryEnd = React.memo(() => (
  <div className="text-center p-2 mb-4" data-testid="message-history-end">
    <Text typography="body2" foreground="hint-100">더 이상 불러올 메시지가 없습니다.</Text>
  </div>
));
MessageHistoryEnd.displayName = 'MessageHistoryEnd';

const EmptyMessages = React.memo(() => (
  <div className="empty-messages">
    <Text typography="body1">아직 메시지가 없습니다.</Text>
    <Text typography="body2" foreground="hint-100">첫 메시지를 보내보세요!</Text>
  </div>
));
EmptyMessages.displayName = 'EmptyMessages';

const VirtualMessageRow = React.memo(({
  index,
  itemKey,
  message,
  renderMessage,
  start,
  totalCount,
}) => {
  return (
    <div
      data-virtual-message-index={index}
      data-virtual-message-key={itemKey}
      aria-posinset={index + 1}
      aria-setsize={totalCount}
      style={{
        display: 'flow-root',
        left: 0,
        position: 'absolute',
        top: 0,
        transform: `translateY(${start}px)`,
        width: '100%',
      }}
    >
      {renderMessage(message)}
    </div>
  );
});
VirtualMessageRow.displayName = 'VirtualMessageRow';

const ChatMessages = ({
  messages = [],
  currentUser = null,
  room = null,
  readCursors = {},
  roomId = null,
  loadingMessages = false,
  hasMoreMessages = true,
  onReactionAdd = () => {},
  onReactionRemove = () => {},
  onLoadMore = () => {}
}) => {
  const currentUserId = currentUser?.id || currentUser?._id;

  // 무한 스크롤 훅
  const { sentinelRef } = useInfiniteScroll(
    onLoadMore,
    hasMoreMessages,
    loadingMessages
  );

  // 자동 스크롤 훅 (스크롤 복원 기능 포함)
  const { containerRef } = useAutoScroll(
    messages,
    currentUserId,
    loadingMessages,
    100 // 하단 100px 이내면 자동 스크롤
  );
  const queueReadReceipt = useReadReceiptBatch({ roomId: roomId || room?._id || room?.id });

  const isMine = useCallback((msg) => {
    if (!msg?.sender || !currentUserId) return false;
    
    return (
      msg.sender._id === currentUserId ||
      msg.sender.id === currentUserId ||
      msg.sender === currentUserId
    );
  }, [currentUserId]);

  // 메시지 상태는 수신·페이지 병합 단계에서 시간순을 보장한다.
  // 렌더마다 전체 목록을 복사하고 다시 정렬하지 않는다.
  const allMessages = Array.isArray(messages) ? messages : [];

  const participants = room?.participants || EMPTY_PARTICIPANTS;
  const participantNamesById = useMemo(() => {
    const names = new Map();
    participants.forEach(participant => {
      const participantId = participant?._id || participant?.id;
      if (participantId) names.set(String(participantId), participant?.name);
    });
    return names;
  }, [participants]);

  const sortedReadTimestamps = useMemo(() => (
    participants
      .map(participant => {
        const cursor = readCursors?.[participant?._id] ?? readCursors?.[participant?.id];
        if (typeof cursor === 'number') return cursor;
        const parsedCursor = Date.parse(cursor);
        return Number.isFinite(parsedCursor) ? parsedCursor : -1;
      })
      .sort((left, right) => left - right)
  ), [participants, readCursors]);

  const getUnreadCount = useCallback((timestamp) => {
    const normalizedTimestamp = typeof timestamp === 'number'
      ? timestamp
      : Date.parse(timestamp);
    if (!Number.isFinite(normalizedTimestamp)) return sortedReadTimestamps.length;

    // 읽음 커서 배열에서 메시지 시각보다 작은 값의 개수를 이진 탐색한다.
    let low = 0;
    let high = sortedReadTimestamps.length;
    while (low < high) {
      const middle = Math.floor((low + high) / 2);
      if (sortedReadTimestamps[middle] < normalizedTimestamp) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }, [sortedReadTimestamps]);

  const getMessageKey = useCallback(
    (message, index) => message?._id || message?.id || `msg-${index}`,
    []
  );
  const {
    isVirtualized,
    listRef,
    measureItems,
    totalSize,
    virtualItems,
  } = useVirtualMessageList({
    items: allMessages,
    containerRef,
    getItemKey: getMessageKey,
  });

  useEffect(() => {
    if (!isVirtualized || !listRef.current) return;

    const list = listRef.current;
    const rows = Array.from(list.querySelectorAll('[data-virtual-message-key]'));
    if (typeof ResizeObserver === 'undefined') {
      const frameId = requestAnimationFrame(() => {
        measureItems(rows.map(element => ({
          key: element.dataset.virtualMessageKey,
          size: element.getBoundingClientRect().height,
        })));
      });
      return () => cancelAnimationFrame(frameId);
    }

    const observer = new ResizeObserver((entries) => {
      measureItems(entries.map(({ target, contentRect }) => ({
        key: target.dataset.virtualMessageKey,
        size: contentRect.height,
      })));
    });
    rows.forEach(row => observer.observe(row));

    return () => observer.disconnect();
  }, [isVirtualized, listRef, measureItems, virtualItems]);

  const rawCurrentUserReadTimestamp = readCursors?.[currentUserId];
  const parsedCurrentUserReadTimestamp = typeof rawCurrentUserReadTimestamp === 'number'
    ? rawCurrentUserReadTimestamp
    : Date.parse(rawCurrentUserReadTimestamp);
  const currentUserReadTimestamp = Number.isFinite(parsedCurrentUserReadTimestamp)
    ? parsedCurrentUserReadTimestamp
    : -1;
  useEffect(() => {
    const container = containerRef.current;
    const messageRoot = isVirtualized ? listRef.current : container;
    if (!container || !messageRoot || !currentUserId || typeof IntersectionObserver === 'undefined') {
      return undefined;
    }

    const readableMessages = Array.from(
      messageRoot.querySelectorAll('[data-readable-message-id]')
    );
    if (readableMessages.length === 0) return undefined;

    const observer = new IntersectionObserver((entries) => {
      let newestVisibleMessage = null;

      entries.forEach(entry => {
        if (!entry.isIntersecting || entry.intersectionRatio < 0.5) return;

        const messageId = entry.target.dataset.readableMessageId;
        const timestamp = Number(entry.target.dataset.readableMessageTimestamp);
        if (!messageId || !Number.isFinite(timestamp) || timestamp <= currentUserReadTimestamp) return;

        if (!newestVisibleMessage || timestamp > newestVisibleMessage.timestamp) {
          newestVisibleMessage = { messageId, timestamp };
        }
      });

      if (newestVisibleMessage) {
        queueReadReceipt(newestVisibleMessage.messageId, newestVisibleMessage.timestamp);
      }
    }, {
      root: container,
      rootMargin: '0px',
      threshold: 0.5,
    });

    readableMessages.forEach(element => observer.observe(element));
    return () => observer.disconnect();
  }, [
    allMessages.length,
    containerRef,
    currentUserId,
    currentUserReadTimestamp,
    isVirtualized,
    listRef,
    queueReadReceipt,
    virtualItems,
  ]);

  const renderMessage = useCallback((msg) => {
    if (!msg) return null;

    const commonProps = {
      currentUser,
      participantNamesById,
      onReactionAdd,
      onReactionRemove,
    };

    const MessageComponent = {
      system: SystemMessage,
      file: FileMessage
    }[msg.type] || UserMessage;

    return (
      <MessageComponent
        {...commonProps}
        msg={msg}
        content={msg.content}
        isMine={msg.type !== 'system' ? isMine(msg) : undefined}
        isStreaming={msg.type === 'ai' ? (msg.isStreaming || false) : undefined}
        unreadCount={msg.type !== 'system' ? getUnreadCount(msg.timestamp) : undefined}
      />
    );
  }, [currentUser, getUnreadCount, isMine, onReactionAdd, onReactionRemove, participantNamesById]);

  const renderedMessages = isVirtualized ? (
    <div
      ref={listRef}
      data-testid="virtual-message-list"
      data-total-message-count={allMessages.length}
      style={{
        flexShrink: 0,
        height: `${totalSize}px`,
        minHeight: `${totalSize}px`,
        position: 'relative',
        width: '100%',
      }}
    >
      {virtualItems.map(({ index, item, key, start }) => (
        <VirtualMessageRow
          key={key}
          index={index}
          itemKey={key}
          message={item}
          renderMessage={renderMessage}
          start={start}
          totalCount={allMessages.length}
        />
      ))}
    </div>
  ) : (
    allMessages.map((msg, idx) => (
      <div
        key={getMessageKey(msg, idx)}
        style={{
          contentVisibility: 'auto',
          containIntrinsicSize: '1px 96px',
        }}
      >
        {renderMessage(msg)}
      </div>
    ))
  );

  return (
    <VStack
      ref={containerRef}
      className="h-full overflow-y-auto overflow-x-hidden [overflow-scrolling:touch]"
      $css={{
        gap: '$200',
        padding: '$300',
      }}
      role="log"
      aria-live="polite"
      aria-atomic="false"
      data-testid="chat-messages-container"
    >
      {/* Sentinel 요소 - 스크롤 맨 위에 배치하여 위로 스크롤 시 이전 메시지 로드 */}
      {hasMoreMessages && (
        <div
          ref={sentinelRef}
          style={{
            height: '20px',
            margin: '10px 0',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center'
          }}
        >
          {loadingMessages && <LoadingIndicator />}
        </div>
      )}

      {!hasMoreMessages && messages.length > 0 && (
        <MessageHistoryEnd />
      )}

      {allMessages.length === 0 ? (
        <EmptyMessages />
      ) : (
        renderedMessages
      )}
    </VStack>
  );
};

ChatMessages.displayName = 'ChatMessages';

export default React.memo(ChatMessages);
