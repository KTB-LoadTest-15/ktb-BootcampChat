import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SocketService } from '../socket';
import { io } from 'socket.io-client';

vi.mock('socket.io-client', () => ({
  io: vi.fn(),
}));

const createSocket = ({ connected = false } = {}) => ({
  connected,
  emit: vi.fn(),
  on: vi.fn(),
  disconnect: vi.fn(),
  io: {
    on: vi.fn(),
    off: vi.fn(),
    opts: {},
  },
});

const flushPromises = async () => {
  // socket.io-client 의 동적 import(코드 스플리팅) job 은 fake timers 환경에서
  // 마이크로태스크만으론 안 풀릴 수 있어, 타이머 큐도 0 으로 함께 민다.
  await vi.advanceTimersByTimeAsync(0);
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
};

const getSocketHandler = (socket, event) =>
  socket.on.mock.calls.find(([registeredEvent]) => registeredEvent === event)?.[1];

const getManagerHandler = (socket, event) =>
  socket.io.on.mock.calls.find(([registeredEvent]) => registeredEvent === event)?.[1];

describe('socketService', () => {
  let service;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    process.env.NEXT_PUBLIC_SOCKET_URL = 'http://localhost:5002';
    service = new SocketService();
  });

  afterEach(() => {
    service.disconnect();
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('rejects a pending connection immediately when disconnected', async () => {
    io.mockReturnValue(createSocket());

    service.connect().catch(() => {});
    const pendingConnection = service.connectionPromise;
    const settledConnection = pendingConnection.then(
      () => 'resolved',
      error => error.message
    );

    // socket.io-client 가 동적 import 라 소켓 세팅은 한 틱 뒤에 이뤄진다.
    await flushPromises();
    service.disconnect();
    await flushPromises();

    await expect(settledConnection).resolves.toBe('Connection disconnected');
    await flushPromises();

    expect(service.connectionPromise).toBeNull();
    expect(service.connectionReject).toBeNull();
    expect(service.connectionTimeout).toBeNull();
  });

  it('registers reconnect lifecycle handlers on the Socket.IO manager', async () => {
    const socket = createSocket();
    io.mockReturnValue(socket);

    service.connect().catch(() => {});
    await flushPromises();

    expect(socket.io.on).toHaveBeenCalledWith('reconnect', expect.any(Function));
    expect(socket.io.on).toHaveBeenCalledWith('reconnect_failed', expect.any(Function));
    expect(socket.on).not.toHaveBeenCalledWith('reconnect', expect.any(Function));
    expect(socket.on).not.toHaveBeenCalledWith('reconnect_failed', expect.any(Function));
  });

  it('does not let a stale manager reconnect failure clear a newer socket', async () => {
    const failedSocket = createSocket();
    const liveSocket = createSocket({ connected: true });
    io.mockReturnValueOnce(failedSocket).mockReturnValueOnce(liveSocket);

    const failedConnection = service.connect().catch(error => error.message);
    await flushPromises();
    getSocketHandler(failedSocket, 'connect_error')(new Error('Invalid session'));
    await flushPromises();

    await expect(failedConnection).resolves.toBe('Invalid session');

    const liveConnection = service.connect();
    await flushPromises();
    getSocketHandler(liveSocket, 'connect')();
    await expect(liveConnection).resolves.toBe(liveSocket);

    getManagerHandler(failedSocket, 'reconnect_failed')();
    await flushPromises();

    expect(service.socket).toBe(liveSocket);
    expect(service.connected).toBe(true);
    expect(liveSocket.disconnect).not.toHaveBeenCalled();
  });

  it('disconnects and clears a failed socket when connection times out', async () => {
    const socket = createSocket();
    io.mockReturnValue(socket);

    const connection = service.connect().catch(error => error.message);
    await flushPromises();

    await vi.advanceTimersByTimeAsync(30000);
    await flushPromises();

    await expect(connection).resolves.toBe('Connection timeout');
    expect(socket.disconnect).toHaveBeenCalledTimes(1);
    expect(service.socket).toBeNull();
    expect(service.connected).toBe(false);
  });

  it('starts a fresh reconnect when reconnect is requested during a pending connection', async () => {
    const pendingSocket = createSocket();
    const reconnectedSocket = createSocket({ connected: true });
    io.mockReturnValueOnce(pendingSocket).mockReturnValueOnce(reconnectedSocket);

    service.connect().catch(() => {});
    const pendingConnection = service.connectionPromise;
    const settledPendingConnection = pendingConnection.then(
      () => 'resolved',
      error => error.message
    );

    await flushPromises();
    const reconnectAttempt = service.reconnect();
    const settledReconnect = reconnectAttempt.then(
      () => 'resolved',
      error => error.message
    );
    await flushPromises();

    await expect(
      Promise.race([
        settledPendingConnection,
        Promise.resolve('pending'),
      ])
    ).resolves.toBe('Connection disconnected');
    expect(service.connectionPromise).toBeNull();
    expect(service.connectionReject).toBeNull();
    expect(service.connectionTimeout).toBeNull();
    expect(pendingSocket.disconnect).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1000);
    await flushPromises();

    expect(io).toHaveBeenCalledTimes(2);
    expect(service.socket).toBe(reconnectedSocket);

    getSocketHandler(reconnectedSocket, 'connect')();
    await expect(settledReconnect).resolves.toBe('resolved');
    expect(service.isReconnecting).toBe(false);
    expect(service.connected).toBe(true);
  });

  it('does not leave transport error reconnect rejections unhandled', async () => {
    const originalReconnect = service.reconnect;
    const consoleLog = vi.spyOn(console, 'log').mockImplementation(() => {});
    service.reconnect = vi.fn(() => Promise.reject(new Error('Reconnect failed')));

    service.handleSocketError({ type: 'TransportError' });
    await flushPromises();

    expect(service.reconnect).toHaveBeenCalledTimes(1);
    expect(consoleLog).toHaveBeenCalledWith('Socket reconnect failed:', 'Reconnect failed');

    service.reconnect = originalReconnect;
    consoleLog.mockRestore();
  });

  it('throws when sending through a disconnected target socket', () => {
    const socket = createSocket({ connected: false });

    expect(() => service.sendOn(socket, 'leaveRoom', 'room-1')).toThrow(
      'Socket is not connected'
    );
    expect(socket.emit).not.toHaveBeenCalled();
  });

  it.each([undefined, null])(
    'throws when sending through a missing target socket: %s',
    (socket) => {
      expect(() => service.sendOn(socket, 'leaveRoom', 'room-1')).toThrow(
        'Socket is not connected'
      );
    }
  );

  it('returns false when trying to send through a disconnected target socket', () => {
    const socket = createSocket({ connected: false });

    expect(service.trySendOn(socket, 'leaveRoom', 'room-1')).toBe(false);
    expect(socket.emit).not.toHaveBeenCalled();
  });

  it.each([undefined, null])(
    'returns false when trying to send through a missing target socket: %s',
    (socket) => {
      expect(service.trySendOn(socket, 'leaveRoom', 'room-1')).toBe(false);
    }
  );

  it('sends through a connected target socket', () => {
    const socket = createSocket({ connected: true });

    service.sendOn(socket, 'leaveRoom', 'room-1');

    expect(socket.emit).toHaveBeenCalledWith('leaveRoom', 'room-1');
    expect(service.trySendOn(socket, 'leaveRoom', 'room-1')).toBe(true);
    expect(socket.emit).toHaveBeenCalledTimes(2);
  });
});
