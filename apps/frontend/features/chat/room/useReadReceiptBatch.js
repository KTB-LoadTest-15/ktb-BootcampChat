import { useCallback, useEffect, useRef } from 'react';
import socketClient from '@/lib/socket/socketClient';

export const READ_RECEIPT_BATCH_DELAY_MS = 200;

export const useReadReceiptBatch = ({
  delayMs = READ_RECEIPT_BATCH_DELAY_MS,
} = {}) => {
  const pendingMessageIdsRef = useRef(new Set());
  const flushTimerRef = useRef(null);

  const flush = useCallback(() => {
    flushTimerRef.current = null;

    const messageIds = Array.from(pendingMessageIdsRef.current);
    pendingMessageIdsRef.current.clear();

    if (messageIds.length === 0 || !socketClient.canSend()) {
      return;
    }

    try {
      socketClient.markMessagesAsRead(messageIds);
    } catch (error) {
      console.error('Error marking messages as read:', error);
    }
  }, []);

  const queueReadReceipt = useCallback((messageId) => {
    if (!messageId || !socketClient.canSend()) {
      return false;
    }

    pendingMessageIdsRef.current.add(messageId);

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
    pendingMessageIdsRef.current.clear();
  }, []);

  return queueReadReceipt;
};

export default useReadReceiptBatch;
