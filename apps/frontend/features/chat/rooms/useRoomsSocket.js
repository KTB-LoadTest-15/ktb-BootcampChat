import { useRef, useEffect } from 'react';
import socketClient from '@/lib/socket/socketClient';

export const CONNECTION_STATUS = {
  CHECKING: 'checking',
  CONNECTING: 'connecting',
  CONNECTED: 'connected',
  DISCONNECTED: 'disconnected',
  ERROR: 'error',
};

export const useRoomsSocket = ({
  currentUser,
  setConnectionStatus,
  setRooms,
  onReconnect,
}) => {
  const socketRef = useRef(null);
  const onReconnectRef = useRef(onReconnect);

  useEffect(() => {
    onReconnectRef.current = onReconnect;
  }, [onReconnect]);

  useEffect(() => {
    if (!currentUser?.token) return;

    let isActive = true;
    let activeSocket = null;
    let activeHandlers = null;

    const connectSocket = async () => {
      setConnectionStatus(CONNECTION_STATUS.CONNECTING);

      try {
        const socket = await socketClient.connect({
          auth: {
            token: currentUser.token,
            sessionId: currentUser.sessionId,
          },
        });

        if (!isActive || !socket) return;

        activeSocket = socket;
        socketRef.current = socket;

        const handlers = {
          connect: () => {
            if (!isActive) return;

            setConnectionStatus(CONNECTION_STATUS.CONNECTED);
            socket.emit('subscribeRoomList');
            onReconnectRef.current?.();
          },
          disconnect: () => {
            setConnectionStatus(CONNECTION_STATUS.DISCONNECTED);
          },
          error: () => {
            setConnectionStatus(CONNECTION_STATUS.ERROR);
          },
          roomCreated: (newRoom) => {
            if (!newRoom?._id) return;

            // 새 방을 맨 앞([newRoom, ...prev])에 넣으면 기존 모든 행의 순번이 +1씩 밀려,
            // 부하 중 순번(nth) 기반 클릭 대상이 클릭 도중 이동한다(옆 버튼이 클릭을 가로채거나
            // 뷰포트 밖으로 밀림). 기존 행 순서를 보존하도록 맨 뒤에 추가하고, 중복 방은 무시한다.
            setRooms((prev) =>
              prev.some((room) => room._id === newRoom._id)
                ? prev
                : [...prev, newRoom]
            );
          },
          roomUpdated: (updatedRoom) => {
            if (!updatedRoom?._id) return;

            // 목록에 없는 방을 상단에 prepend하면(index === -1) 기존 행들의 순번이 밀려
            // 순번(nth) 기반 클릭 대상이 흔들린다. 새 방은 roomCreated가 담당하므로,
            // 여기서는 보이는 방의 정보만 제자리에서 갱신한다(순서 불변).
            setRooms((prev) =>
              prev.map((room) =>
                room._id === updatedRoom._id ? updatedRoom : room
              )
            );
          },
          // 활성도 지표만 담긴 경량 payload이므로 방 정보를 덮지 않고 병합한다
          roomActivity: (activity) => {
            if (!activity?._id) return;

            setRooms((prev) =>
              prev.map((room) =>
                room._id === activity._id
                  ? { ...room, recentMessageCount: activity.recentMessageCount }
                  : room
              )
            );
          },
        };
        activeHandlers = handlers;

        Object.entries(handlers).forEach(([event, handler]) => {
          socket.on(event, handler);
        });

        // connect()가 resolve된 시점의 소켓은 이미 handshake를 마친 상태다.
        // 이후 상태 전이는 connect/disconnect/error 이벤트로만 갱신한다.
        if (socket.connected) {
          setConnectionStatus(CONNECTION_STATUS.CONNECTED);
        }
        socket.emit('subscribeRoomList');
      } catch (error) {
        if (!isActive) return;

        console.log('Socket connection error:', error);

        if (
          error.message?.includes('Authentication required') ||
          error.message?.includes('Invalid session')
        ) {
          // Auth error will be handled by the useAuth context
        }

        setConnectionStatus(CONNECTION_STATUS.ERROR);
      }
    };

    connectSocket();

    return () => {
      isActive = false;

      if (activeSocket && activeHandlers) {
        Object.entries(activeHandlers).forEach(([event, handler]) => {
          activeSocket.off(event, handler);
        });
      }

      if (activeSocket?.connected) {
        activeSocket.emit('unsubscribeRoomList');
      }

      if (socketRef.current === activeSocket) {
        socketRef.current = null;
      }
    };
  }, [currentUser]); // eslint-disable-line react-hooks/exhaustive-deps

  return { socketRef };
};

export default useRoomsSocket;
