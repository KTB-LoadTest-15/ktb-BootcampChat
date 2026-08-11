import { useCallback, useEffect, useRef } from 'react';
import socketClient from '@/lib/socket/socketClient';

export const READ_RECEIPT_BATCH_DELAY_MS = 200;

/**
 * 읽음 처리 송신 배칭 (read cursor 방식).
 *
 * <p>메시지 id 목록 대신 "가장 최근에 읽은 메시지"(timestamp 최댓값의 메시지) id 하나만 배칭해
 * 전송한다. 서버가 그 메시지의 서버 timestamp로 커서를 전진시키므로, 뷰포트에 여러 메시지가
 * 들어와도 flush 때 최상단 1건만 나가고 하위 메시지는 자동으로 읽음 처리된다. timestamp 자체를
 * 보내지 않아 위조가 불가능하다.
 */
export const useReadReceiptBatch = ({
  roomId,
  delayMs = READ_RECEIPT_BATCH_DELAY_MS,
} = {}) => {
  const pendingRef = useRef({ maxTs: -Infinity, messageId: null });
  const flushTimerRef = useRef(null);
  // flush 클로저가 최신 roomId를 보도록 ref로 고정한다.
  const roomIdRef = useRef(roomId);
  roomIdRef.current = roomId;

  const flush = useCallback(() => {
    flushTimerRef.current = null;

    const { messageId } = pendingRef.current;
    pendingRef.current = { maxTs: -Infinity, messageId: null };

    const currentRoomId = roomIdRef.current;
    if (!messageId || !currentRoomId || !socketClient.canSend()) {
      return;
    }

    try {
      socketClient.markMessagesAsRead(currentRoomId, messageId);
    } catch (error) {
      console.error('Error marking messages as read:', error);
    }
  }, []);

  const queueReadReceipt = useCallback((messageId, timestamp) => {
    if (!messageId || typeof timestamp !== 'number' || !Number.isFinite(timestamp) || !socketClient.canSend()) {
      return false;
    }

    if (timestamp > pendingRef.current.maxTs) {
      pendingRef.current = { maxTs: timestamp, messageId };
    }

    if (flushTimerRef.current === null) {
      flushTimerRef.current = setTimeout(flush, delayMs);
    }

    return true;
  }, [delayMs, flush]);

  useEffect(() => () => {
    if (flushTimerRef.current !== null) {
      clearTimeout(flushTimerRef.current);
      flushTimerRef.current = null;
    }
    pendingRef.current = { maxTs: -Infinity, messageId: null };
  }, []);

  return queueReadReceipt;
};

export default useReadReceiptBatch;
