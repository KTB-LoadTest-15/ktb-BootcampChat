import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import socketClient from '@/lib/socket/socketClient';
import { useReadReceiptBatch } from '../useReadReceiptBatch';

vi.mock('@/lib/socket/socketClient', () => ({
  default: {
    canSend: vi.fn(() => true),
    markMessagesAsRead: vi.fn(),
  },
}));

describe('useReadReceiptBatch', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    socketClient.canSend.mockReturnValue(true);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('deduplicates message IDs and sends them in one batch', () => {
    const { result } = renderHook(() => useReadReceiptBatch({ delayMs: 200 }));

    act(() => {
      expect(result.current('message-1')).toBe(true);
      expect(result.current('message-2')).toBe(true);
      expect(result.current('message-1')).toBe(true);
      vi.advanceTimersByTime(199);
    });

    expect(socketClient.markMessagesAsRead).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1);
    });

    expect(socketClient.markMessagesAsRead).toHaveBeenCalledTimes(1);
    expect(socketClient.markMessagesAsRead).toHaveBeenCalledWith([
      'message-1',
      'message-2',
    ]);
  });

  it('does not queue a receipt while the socket cannot send', () => {
    socketClient.canSend.mockReturnValue(false);
    const { result } = renderHook(() => useReadReceiptBatch({ delayMs: 200 }));

    act(() => {
      expect(result.current('message-1')).toBe(false);
      vi.runAllTimers();
    });

    expect(socketClient.markMessagesAsRead).not.toHaveBeenCalled();
  });

  it('discards pending receipts when the message list unmounts', () => {
    const { result, unmount } = renderHook(() =>
      useReadReceiptBatch({ delayMs: 200 })
    );

    act(() => {
      result.current('message-1');
    });
    unmount();

    act(() => {
      vi.runAllTimers();
    });

    expect(socketClient.markMessagesAsRead).not.toHaveBeenCalled();
  });
});
