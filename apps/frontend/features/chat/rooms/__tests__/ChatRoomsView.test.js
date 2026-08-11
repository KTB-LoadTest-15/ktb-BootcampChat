import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ChatRoomsView, {
  ROOM_LIST_REFRESH_INTERVAL,
  ROOM_LIST_STALE_AFTER_MS,
} from '../ChatRoomsView';
import { CONNECTION_STATUS } from '../useServerConnection';
import { useRoomsSocket } from '../useRoomsSocket';

const mocks = vi.hoisted(() => ({
  connectionStatus: 'checking',
  error: null,
  fetchRooms: vi.fn(() => Promise.resolve()),
  refreshRooms: vi.fn(() => Promise.resolve(true)),
}));

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: {
      id: 'user-1',
      token: 'token-1',
      sessionId: 'session-1',
    },
  }),
}));

vi.mock('../useServerConnection', async () => {
  const actual = await vi.importActual('../useServerConnection');
  return {
    ...actual,
    useServerConnection: () => ({
      connectionStatus: mocks.connectionStatus,
      setConnectionStatus: vi.fn(),
      attemptConnection: vi.fn(() => Promise.resolve(true)),
    }),
  };
});

vi.mock('../useRoomList', () => ({
  useRoomList: () => ({
    rooms: [],
    setRooms: vi.fn(),
    error: mocks.error,
    loading: false,
    refreshing: false,
    joiningRoom: false,
    fetchRooms: mocks.fetchRooms,
    refreshRooms: mocks.refreshRooms,
    handleJoinRoom: vi.fn(),
  }),
}));

vi.mock('../useRoomsSocket', () => ({
  useRoomsSocket: vi.fn(),
}));

describe('ChatRoomsView', () => {
  beforeEach(() => {
    vi.spyOn(Math, 'random').mockReturnValue(0);
    mocks.connectionStatus = CONNECTION_STATUS.CHECKING;
    mocks.error = null;
    mocks.fetchRooms.mockClear();
    mocks.refreshRooms.mockReset().mockResolvedValue(true);
    useRoomsSocket.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('does not refetch rooms when connection status changes after the initial load starts', async () => {
    const { rerender } = render(<ChatRoomsView router={{ push: vi.fn() }} />);

    await waitFor(() => {
      expect(mocks.fetchRooms).toHaveBeenCalledTimes(1);
    });

    mocks.connectionStatus = CONNECTION_STATUS.CONNECTED;
    rerender(<ChatRoomsView router={{ push: vi.fn() }} />);

    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(mocks.fetchRooms).toHaveBeenCalledTimes(1);
  });

  it('refreshes the room list on an interval while connected', async () => {
    mocks.connectionStatus = CONNECTION_STATUS.CONNECTED;
    vi.useFakeTimers();

    render(<ChatRoomsView router={{ push: vi.fn() }} />);

    await vi.advanceTimersByTimeAsync(ROOM_LIST_REFRESH_INTERVAL);

    expect(mocks.refreshRooms).toHaveBeenCalledWith({
      silent: true,
      staleAfterMs: ROOM_LIST_STALE_AFTER_MS,
      maxRetries: 0,
    });
  });

  it('does not auto refresh while the server connection is not established', async () => {
    mocks.connectionStatus = CONNECTION_STATUS.DISCONNECTED;
    vi.useFakeTimers();

    render(<ChatRoomsView router={{ push: vi.fn() }} />);

    await vi.advanceTimersByTimeAsync(90000);

    expect(mocks.refreshRooms).not.toHaveBeenCalled();
  });

  it('catches up as soon as the tab becomes visible again', async () => {
    mocks.connectionStatus = CONNECTION_STATUS.CONNECTED;

    render(<ChatRoomsView router={{ push: vi.fn() }} />);

    await waitFor(() => {
      expect(mocks.fetchRooms).toHaveBeenCalled();
    });

    document.dispatchEvent(new Event('visibilitychange'));

    expect(mocks.refreshRooms).toHaveBeenCalledWith({
      silent: true,
      staleAfterMs: ROOM_LIST_STALE_AFTER_MS,
      maxRetries: 0,
    });
  });

  it('backs off the next poll after a failed background refresh', async () => {
    mocks.connectionStatus = CONNECTION_STATUS.CONNECTED;
    mocks.refreshRooms
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(true);
    vi.useFakeTimers();

    render(<ChatRoomsView router={{ push: vi.fn() }} />);

    await vi.advanceTimersByTimeAsync(ROOM_LIST_REFRESH_INTERVAL);
    expect(mocks.refreshRooms).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(ROOM_LIST_REFRESH_INTERVAL * 2 - 1);
    expect(mocks.refreshRooms).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1);
    expect(mocks.refreshRooms).toHaveBeenCalledTimes(2);
  });

  it('forces one room list sync when the socket reconnects', async () => {
    mocks.connectionStatus = CONNECTION_STATUS.CONNECTED;

    render(<ChatRoomsView router={{ push: vi.fn() }} />);

    const { onReconnect } = useRoomsSocket.mock.calls.at(-1)[0];

    await act(async () => {
      await onReconnect();
    });

    expect(mocks.refreshRooms).toHaveBeenCalledTimes(1);
    expect(mocks.refreshRooms).toHaveBeenCalledWith({
      silent: true,
      maxRetries: 0,
    });
  });

  it('refreshes the list when the refresh button is clicked', async () => {
    mocks.connectionStatus = CONNECTION_STATUS.CONNECTED;

    render(<ChatRoomsView router={{ push: vi.fn() }} />);

    fireEvent.click(await screen.findByTestId('refresh-rooms-button'));

    expect(mocks.refreshRooms).toHaveBeenCalledTimes(1);
    expect(mocks.refreshRooms).toHaveBeenCalledWith();
  });

  it('offers reconnect instead of refresh while an error is shown', async () => {
    mocks.connectionStatus = CONNECTION_STATUS.ERROR;
    mocks.error = { title: '연결 오류', message: '서버와 연결할 수 없습니다.', type: 'danger' };

    render(<ChatRoomsView router={{ push: vi.fn() }} />);

    await waitFor(() => {
      expect(screen.getByText('재연결')).toBeTruthy();
    });

    expect(screen.queryByTestId('refresh-rooms-button')).toBeNull();
  });
});
