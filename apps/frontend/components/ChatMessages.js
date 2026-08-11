import React, { useCallback, useLayoutEffect, useMemo } from 'react';
import { Spinner, Text, VStack } from '@vapor-ui/core';
import SystemMessage from './SystemMessage';
import FileMessage from './FileMessage';
import UserMessage from './UserMessage';
import { useInfiniteScroll } from '../hooks/useInfiniteScroll';
import { useAutoScroll } from '../hooks/useAutoScroll';
import { useVirtualMessageList } from '../hooks/useVirtualMessageList';
import { useReadReceiptBatch } from '../features/chat/room/useReadReceiptBatch';

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
  children,
  index,
  itemKey,
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
      {children}
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
  const currentUserId = currentUser?.id;

  // 무한 스크롤 훅
  const { sentinelRef } = useInfiniteScroll(
    onLoadMore,
    hasMoreMessages,
    loadingMessages
  );

  // 자동 스크롤 훅 (스크롤 복원 기능 포함)
  const { containerRef, scrollToBottom, isNearBottom } = useAutoScroll(
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

  const allMessages = useMemo(() => {
    if (!Array.isArray(messages)) return [];

    return [...messages].sort((a, b) => {
      if (!a?.timestamp || !b?.timestamp) return 0;
      return new Date(a.timestamp) - new Date(b.timestamp);
    });
  }, [messages]);

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

  useLayoutEffect(() => {
    if (!isVirtualized || !listRef.current) return;

    const list = listRef.current;
    const rows = Array.from(list.querySelectorAll('[data-virtual-message-key]'));
    const measureRows = (elements) => {
      measureItems(elements.map(element => ({
        key: element.dataset.virtualMessageKey,
        size: element.getBoundingClientRect().height,
      })));
    };
    measureRows(rows);

    if (typeof ResizeObserver === 'undefined') return;

    const observer = new ResizeObserver((entries) => {
      measureItems(entries.map(({ target, contentRect }) => ({
        key: target.dataset.virtualMessageKey,
        size: contentRect.height,
      })));
    });
    rows.forEach(row => observer.observe(row));

    return () => observer.disconnect();
  }, [isVirtualized, listRef, measureItems, virtualItems]);

  const renderMessage = useCallback((msg) => {
    if (!msg) return null;

    const commonProps = {
      currentUser,
      room,
      cursors: readCursors,
      onReactionAdd,
      onReactionRemove,
      onMessageRead: queueReadReceipt,
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
      />
    );
  }, [currentUser, room, readCursors, isMine, onReactionAdd, onReactionRemove, queueReadReceipt]);

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
          start={start}
          totalCount={allMessages.length}
        >
          {renderMessage(item)}
        </VirtualMessageRow>
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
      className="h-full overflow-y-auto overflow-x-hidden scroll-smooth [overflow-scrolling:touch]"
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
