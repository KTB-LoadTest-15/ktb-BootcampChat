import {
  collectUniqueMessages,
  mergeSortedMessages,
} from '../messages/useMessageList';

export const MESSAGE_BATCH_DELAY_MS = 16;

export const processLoadedRoomMessages = ({
  loadedMessages,
  hasMore,
  isInitialLoad = false,
  processedMessageIds,
  setMessages,
  setHasMoreMessages,
  initialLoadCompletedRef,
}) => {
  if (!Array.isArray(loadedMessages)) {
    throw new Error('Invalid messages format');
  }

  const {
    messages: uniqueLoadedMessages,
    processedMessageIds: nextProcessedMessageIds,
  } = collectUniqueMessages(loadedMessages, processedMessageIds.current);
  processedMessageIds.current = nextProcessedMessageIds;

  let nextMessages;
  setMessages(prev => {
    nextMessages = mergeSortedMessages(prev, uniqueLoadedMessages);
    return nextMessages;
  });
  setHasMoreMessages(hasMore);

  if (isInitialLoad) {
    initialLoadCompletedRef.current = true;
  }

  return nextMessages;
};

/**
 * 읽음 커서 맵을 단조 병합한다. read cursor 방식.
 *
 * <p>메시지 배열을 O(n) 재생성하던 방식(applyReadReceipts)을 대체한다. 방에 N명이 있어도
 * 읽음 이벤트당 커서 맵의 한 칸만 갱신하며, 역행/중복이면 동일 참조를 반환해 리렌더를 막는다.
 */
export const mergeReadCursor = (cursors, { userId, lastReadTs }) => {
  if (!userId || typeof lastReadTs !== 'number') {
    return cursors;
  }
  const current = cursors?.[userId];
  if (current !== undefined && current >= lastReadTs) {
    return cursors;
  }
  return { ...cursors, [userId]: lastReadTs };
};

/** 커서 맵 전체를 단조 병합한다(입장 시 seed·재입장 시 로컬 진행 보존). */
export const mergeReadCursorMap = (cursors, incoming) => {
  if (!incoming) return cursors || {};
  let next = cursors || {};
  for (const [userId, lastReadTs] of Object.entries(incoming)) {
    next = mergeReadCursor(next, { userId, lastReadTs });
  }
  return next;
};

export const createRoomEventHandlers = ({
  mountedRef,
  messageProcessingRef,
  processedMessageIds,
  initialLoadCompletedRef,
  processMessages,
  setRoom,
  setMessages,
  setReadCursors,
  setLoadingMessages,
  setError,
  setHasMoreMessages,
  cleanup,
  logout,
  onReplace,
  handleReactionUpdate,
  showRejectedMessage,
  scheduleMessageFlush = callback => setTimeout(callback, MESSAGE_BATCH_DELAY_MS),
  cancelMessageFlush = handle => clearTimeout(handle),
}) => {
  let pendingMessages = [];
  let pendingReadCursors = {};
  let messageFlushHandle = null;

  const flushPendingMessages = () => {
    messageFlushHandle = null;

    if (!mountedRef.current) {
      pendingMessages = [];
      pendingReadCursors = {};
      return;
    }

    const messagesToAppend = pendingMessages;
    const readCursorsToMerge = pendingReadCursors;
    pendingMessages = [];
    pendingReadCursors = {};

    if (messagesToAppend.length > 0) {
      setMessages(previousMessages => (
        mergeSortedMessages(previousMessages, messagesToAppend)
      ));
    }
    if (Object.keys(readCursorsToMerge).length > 0) {
      setReadCursors(previousCursors => (
        mergeReadCursorMap(previousCursors, readCursorsToMerge)
      ));
    }
  };

  const schedulePendingMessageFlush = () => {
    if (messageFlushHandle !== null) return;
    messageFlushHandle = scheduleMessageFlush(flushPendingMessages);
  };

  const dispose = () => {
    if (messageFlushHandle !== null) {
      cancelMessageFlush(messageFlushHandle);
      messageFlushHandle = null;
    }
    pendingMessages = [];
    pendingReadCursors = {};
  };

  const handlePreviousMessages = (response) => {
    if (!mountedRef.current || messageProcessingRef.current) return;
    try {
      messageProcessingRef.current = true;
      if (!response || typeof response !== 'object') {
        throw new Error('Invalid response format');
      }
      const { messages: loadedMessages = [], hasMore } = response;
      const isInitialLoad = !initialLoadCompletedRef.current;
      processMessages(loadedMessages, hasMore, isInitialLoad);
      setLoadingMessages(false);
    } catch (error) {
      setLoadingMessages(false);
      setError('메시지 처리 중 오류가 발생했습니다.');
      setHasMoreMessages(false);
    } finally {
      messageProcessingRef.current = false;
    }
  };

  return {
    dispose,
    flushPendingMessages,
    onParticipantsUpdate: (participants) => {
      if (!mountedRef.current) return;
      setRoom(prev => ({ ...prev, participants: participants || [] }));
    },
    onMessagesRead: (payload) => {
      if (!mountedRef.current || !payload?.cursors) return;
      pendingReadCursors = mergeReadCursorMap(pendingReadCursors, payload.cursors);
      schedulePendingMessageFlush();
    },
    onMessage: (incoming) => {
      if (!mountedRef.current || messageProcessingRef.current) return;
      if (!incoming?._id || processedMessageIds.current.has(incoming._id)) return;
      processedMessageIds.current.add(incoming._id);
      pendingMessages.push(incoming);
      schedulePendingMessageFlush();
    },
    onPreviousMessagesLoaded: handlePreviousMessages,
    onMessageReactionUpdate: (data) => {
      if (!mountedRef.current) return;
      handleReactionUpdate(data);
    },
    onSessionEnded: () => {
      if (!mountedRef.current) return;
      dispose();
      cleanup();
      logout();
      onReplace('/?error=session_expired');
    },
    onError: (error) => {
      if (!mountedRef.current) return;
      console.error('Socket error:', error);
      if (error?.code === 'MESSAGE_REJECTED') {
        showRejectedMessage(error.message || '금칙어가 포함되어 메시지를 전송할 수 없습니다.');
        return;
      }
      setError(error.message || '채팅 연결에 문제가 발생했습니다.');
    },
  };
};
