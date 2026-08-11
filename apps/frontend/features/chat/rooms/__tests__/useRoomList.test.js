import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import axiosInstance from '@/services/axios';
import ensureSocketReady from '@/lib/socket/ensureSocketReady';
import { useRoomList } from '../useRoomList';
import { API_STATUS } from '../useServerConnection';
import { CONNECTION_STATUS } from '../useRoomsSocket';

vi.mock('@/services/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

vi.mock('@/lib/socket/ensureSocketReady', () => ({
  default: vi.fn(() => Promise.resolve({ id: 'socket-1', connected: true })),
}));

const roomsResponse = (rooms) => ({ data: { data: rooms } });

const renderRoomList = ({
  router = { push: vi.fn() },
  connectionStatus = CONNECTION_STATUS.CONNECTED,
  attemptConnection = vi.fn(() => Promise.resolve(true)),
} = {}) =>
  renderHook(() =>
    useRoomList({
      currentUser: { token: 'token-1' },
      router,
      apiStatus: API_STATUS.HEALTHY,
      setApiStatus: vi.fn(),
      connectionStatus,
      attemptConnection,
    })
  );

describe('useRoomList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    window.history.pushState({}, '', '/');
  });

  it('logs the join response and completed navigation with one trace', async () => {
    vi.useFakeTimers();
    const consoleInfo = vi.spyOn(console, 'info').mockImplementation(() => {});
    const router = {
      push: vi.fn((path) => {
        window.history.pushState({}, '', path);
      }),
    };
    axiosInstance.post.mockResolvedValue({
      status: 200,
      data: { success: true },
      config: { retryCount: 1 },
    });

    const { result } = renderRoomList({ router });

    await act(async () => {
      await result.current.handleJoinRoom('room-1');
      await vi.runAllTimersAsync();
    });

    const metricLogs = consoleInfo.mock.calls.map(([message]) => message);
    const responseLog = metricLogs.find((message) => message.includes('"event":"request_complete"'));
    const navigationLog = metricLogs.find((message) => message.includes('"event":"navigation_complete"'));

    expect(axiosInstance.post).toHaveBeenCalledWith('/api/rooms/room-1/join', {});
    expect(ensureSocketReady).toHaveBeenCalledWith({
      currentUser: { token: 'token-1' },
      attemptConnection: expect.any(Function),
    });
    expect(router.push).toHaveBeenCalledWith('/chat/room-1');
    expect(responseLog).toContain('"status":200');
    expect(responseLog).toContain('"retryCount":1');
    expect(navigationLog).toContain('"pathname":"/chat/room-1"');

  });

  it('reconnects the socket before entering a room even when the badge says disconnected', async () => {
    const router = { push: vi.fn() };
    axiosInstance.post.mockResolvedValue({
      status: 200,
      data: { success: true },
      config: { retryCount: 0 },
    });

    const { result } = renderRoomList({
      router,
      connectionStatus: CONNECTION_STATUS.DISCONNECTED,
    });

    await act(async () => {
      await result.current.handleJoinRoom('room-2');
    });

    expect(ensureSocketReady).toHaveBeenCalledTimes(1);
    expect(router.push).toHaveBeenCalledWith('/chat/room-2');
    expect(result.current.error).toBeNull();
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
