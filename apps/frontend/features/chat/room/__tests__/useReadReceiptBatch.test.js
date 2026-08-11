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

  it('sends the id of the newest read message once, with roomId', () => {
    const { result } = renderHook(() =>
      useReadReceiptBatch({ roomId: 'room-1', delayMs: 200 })
    );

    act(() => {
      expect(result.current('message-a', 1000)).toBe(true);
      expect(result.current('message-c', 3000)).toBe(true); // 최댓값 ts
      expect(result.current('message-b', 2000)).toBe(true);
      vi.advanceTimersByTime(199);
    });

    expect(socketClient.markMessagesAsRead).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1);
    });

    // ts가 아니라 최신 메시지의 id를 보낸다(서버가 서버 timestamp로 커서 전진).
    expect(socketClient.markMessagesAsRead).toHaveBeenCalledTimes(1);
    expect(socketClient.markMessagesAsRead).toHaveBeenCalledWith('room-1', 'message-c');
  });

  it('does not queue a receipt while the socket cannot send', () => {
    socketClient.canSend.mockReturnValue(false);
    const { result } = renderHook(() =>
      useReadReceiptBatch({ roomId: 'room-1', delayMs: 200 })
    );

    act(() => {
      expect(result.current('message-a', 1000)).toBe(false);
      vi.runAllTimers();
    });

    expect(socketClient.markMessagesAsRead).not.toHaveBeenCalled();
  });

  it('does not send when no roomId is available', () => {
    const { result } = renderHook(() => useReadReceiptBatch({ delayMs: 200 }));

    act(() => {
      expect(result.current('message-a', 1000)).toBe(true);
      vi.runAllTimers();
    });

    expect(socketClient.markMessagesAsRead).not.toHaveBeenCalled();
  });

  it('discards pending receipts when the message list unmounts', () => {
    const { result, unmount } = renderHook(() =>
      useReadReceiptBatch({ roomId: 'room-1', delayMs: 200 })
    );

    act(() => {
      result.current('message-a', 1000);
    });
    unmount();

    act(() => {
      vi.runAllTimers();
    });

    expect(socketClient.markMessagesAsRead).not.toHaveBeenCalled();
  });
});
