import { useCallback, useEffect, useRef } from 'react';
import socketClient from '@/lib/socket/socketClient';

export const READ_RECEIPT_BATCH_DELAY_MS = 200;

/**
 * 읽음 처리 송신 배칭 (read cursor 방식).
 *
 * <p>메시지 id 목록 대신 "마지막으로 읽은 메시지의 timestamp(최댓값)" 하나만 배칭해 전송한다.
 * 뷰포트에 여러 메시지가 들어와도 flush 때 최댓값 1건만 나가며, 커서가 상위 timestamp면
 * 하위 메시지는 자동으로 읽음 처리된다.
 */
export const useReadReceiptBatch = ({
  roomId,
  delayMs = READ_RECEIPT_BATCH_DELAY_MS,
} = {}) => {
  const pendingMaxTsRef = useRef(0);
  const flushTimerRef = useRef(null);
  // flush 클로저가 최신 roomId를 보도록 ref로 고정한다.
  const roomIdRef = useRef(roomId);
  roomIdRef.current = roomId;

  const flush = useCallback(() => {
    flushTimerRef.current = null;

    const lastReadTs = pendingMaxTsRef.current;
    pendingMaxTsRef.current = 0;

    const currentRoomId = roomIdRef.current;
    if (lastReadTs <= 0 || !currentRoomId || !socketClient.canSend()) {
      return;
    }

    try {
      socketClient.markMessagesAsRead(currentRoomId, lastReadTs);
    } catch (error) {
      console.error('Error marking messages as read:', error);
    }
  }, []);

  const queueReadReceipt = useCallback((timestamp) => {
    if (typeof timestamp !== 'number' || !Number.isFinite(timestamp) || !socketClient.canSend()) {
      return false;
    }

    if (timestamp > pendingMaxTsRef.current) {
      pendingMaxTsRef.current = timestamp;
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
    pendingMaxTsRef.current = 0;
  }, []);

  return queueReadReceipt;
};

export default useReadReceiptBatch;
