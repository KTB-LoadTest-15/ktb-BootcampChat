import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import axiosInstance from '@/services/axios';
import { useRoomList } from '../useRoomList';
import { CONNECTION_STATUS } from '../useServerConnection';

vi.mock('@/services/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const roomsResponse = (rooms) => ({ data: { data: rooms } });

const renderRoomList = () =>
  renderHook(() =>
    useRoomList({
      currentUser: { token: 'token-1' },
      router: { push: vi.fn() },
      connectionStatus: CONNECTION_STATUS.CONNECTED,
      setConnectionStatus: vi.fn(),
    })
  );

describe('useRoomList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('replaces the list on refresh without leaving the refreshing flag on', async () => {
    axiosInstance.get.mockResolvedValue(roomsResponse([{ _id: 'room-1' }]));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.refreshing).toBe(false);
  });

  it('keeps the current list and stays quiet when a silent refresh fails', async () => {
    axiosInstance.get.mockResolvedValueOnce(roomsResponse([{ _id: 'room-1' }]));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });

    axiosInstance.get.mockRejectedValueOnce(new Error('SERVER_UNREACHABLE'));

    await act(async () => {
      await result.current.refreshRooms({ silent: true });
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('surfaces a refresh failure when the user asked for it', async () => {
    axiosInstance.get.mockRejectedValue(new Error('SERVER_UNREACHABLE'));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).toMatchObject({
      title: '채팅방 목록 갱신 실패',
      showRetry: false,
    });
  });

  it('clears a previous error once a refresh succeeds', async () => {
    axiosInstance.get.mockRejectedValueOnce(new Error('SERVER_UNREACHABLE'));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).not.toBeNull();

    axiosInstance.get.mockResolvedValueOnce(roomsResponse([{ _id: 'room-1' }]));

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).toBeNull();
    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
  });

  it('reuses the in-flight room list request for concurrent refreshes', async () => {
    let resolveRequest;
    axiosInstance.get.mockReturnValue(
      new Promise((resolve) => {
        resolveRequest = resolve;
      })
    );

    const { result } = renderRoomList();

    await act(async () => {
      const firstRefresh = result.current.refreshRooms();
      const secondRefresh = result.current.refreshRooms({ silent: true });

      expect(axiosInstance.get).toHaveBeenCalledTimes(1);

      resolveRequest(roomsResponse([{ _id: 'room-1' }]));
      await Promise.all([firstRefresh, secondRefresh]);
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
  });

  it('skips a background refresh while the last successful result is fresh', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-11T00:00:00Z'));
    axiosInstance.get.mockResolvedValue(roomsResponse([{ _id: 'room-1' }]));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
      await result.current.refreshRooms({
        silent: true,
        staleAfterMs: 120000,
        maxRetries: 0,
      });
    });

    expect(axiosInstance.get).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(120000);

    await act(async () => {
      await result.current.refreshRooms({
        silent: true,
        staleAfterMs: 120000,
        maxRetries: 0,
      });
    });

    expect(axiosInstance.get).toHaveBeenCalledTimes(2);
    expect(axiosInstance.get).toHaveBeenLastCalledWith('/api/rooms', {
      maxRetries: 0,
    });

    vi.useRealTimers();
  });
});
