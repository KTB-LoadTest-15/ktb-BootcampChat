import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import socketClient from '@/lib/socket/socketClient';
import { useRoomsSocket } from '../useRoomsSocket';

vi.mock('@/lib/socket/socketClient', () => ({
  default: {
    connect: vi.fn(),
  },
}));

const currentUser = {
  token: 'token-1',
  sessionId: 'session-1',
};

const renderRoomsSocket = (socket, overrides = {}) => {
  socketClient.connect.mockResolvedValue(socket);

  return renderHook(() =>
    useRoomsSocket({
      currentUser,
      router: { push: vi.fn() },
      setConnectionStatus: vi.fn(),
      setRooms: vi.fn(),
      ...overrides,
    })
  );
};

const createSocket = () => ({
  connected: true,
  on: vi.fn(),
  off: vi.fn(),
  emit: vi.fn(),
  disconnect: vi.fn(),
});

const handlerFor = (socket, event) =>
  socket.on.mock.calls.find(([registered]) => registered === event)[1];

describe('useRoomsSocket', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('subscribes to room-list on mount and resubscribes after reconnect', async () => {
    const socket = createSocket();
    const setConnectionStatus = vi.fn();
    const onReconnect = vi.fn();

    renderRoomsSocket(socket, { setConnectionStatus, onReconnect });

    await waitFor(() => {
      expect(socket.emit).toHaveBeenCalledWith('subscribeRoomList');
    });

    expect(socket.emit).toHaveBeenCalledTimes(1);
    expect(setConnectionStatus).toHaveBeenCalledWith('connected');

    handlerFor(socket, 'connect')();

    expect(socket.emit).toHaveBeenCalledTimes(2);
    expect(socket.emit).toHaveBeenLastCalledWith('subscribeRoomList');
    expect(onReconnect).toHaveBeenCalledTimes(1);
  });

  it('unsubscribes and removes list listeners without disconnecting the shared socket', async () => {
    const socket = createSocket();
    const { unmount } = renderRoomsSocket(socket);

    await waitFor(() => {
      expect(socket.emit).toHaveBeenCalledWith('subscribeRoomList');
    });

    const registeredHandlers = [...socket.on.mock.calls];

    unmount();

    expect(socket.emit).toHaveBeenLastCalledWith('unsubscribeRoomList');
    expect(socket.disconnect).not.toHaveBeenCalled();
    for (const [event, handler] of registeredHandlers) {
      expect(socket.off).toHaveBeenCalledWith(event, handler);
    }
  });

  it('does not register roomDeleted without a server-side room delete event', async () => {
    const socket = createSocket();

    renderRoomsSocket(socket);

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalled();
    });

    const registeredEvents = socket.on.mock.calls.map(([event]) => event);
    expect(registeredEvents).not.toContain('roomDeleted');
  });

  it('merges a roomActivity update into the matching room without dropping its other fields', async () => {
    const socket = createSocket();
    const setRooms = vi.fn();

    renderRoomsSocket(socket, { setRooms });

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalledWith('roomActivity', expect.any(Function));
    });

    handlerFor(socket, 'roomActivity')({ _id: 'room-2', recentMessageCount: 9 });

    const updateRooms = setRooms.mock.calls[0][0];

    expect(
      updateRooms([
        { _id: 'room-1', name: '방1', recentMessageCount: 1 },
        { _id: 'room-2', name: '방2', recentMessageCount: 2 },
      ])
    ).toEqual([
      { _id: 'room-1', name: '방1', recentMessageCount: 1 },
      { _id: 'room-2', name: '방2', recentMessageCount: 9 },
    ]);
  });

  it('ignores a roomActivity payload without a room id', async () => {
    const socket = createSocket();
    const setRooms = vi.fn();

    renderRoomsSocket(socket, { setRooms });

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalledWith('roomActivity', expect.any(Function));
    });

    handlerFor(socket, 'roomActivity')(undefined);

    expect(setRooms).not.toHaveBeenCalled();
  });

  it('upserts a roomUpdated payload when the room is missing from the current list', async () => {
    const socket = createSocket();
    const setRooms = vi.fn();

    renderRoomsSocket(socket, { setRooms });

    await waitFor(() => {
      expect(socket.on).toHaveBeenCalledWith('roomUpdated', expect.any(Function));
    });

    handlerFor(socket, 'roomUpdated')({ _id: 'room-2', name: '방2' });

    const updateRooms = setRooms.mock.calls[0][0];

    expect(updateRooms([{ _id: 'room-1', name: '방1' }])).toEqual([
      { _id: 'room-2', name: '방2' },
      { _id: 'room-1', name: '방1' },
    ]);
  });
});
