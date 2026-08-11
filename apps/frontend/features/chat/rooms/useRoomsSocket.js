import { useRef, useEffect } from 'react';
import socketClient from '@/lib/socket/socketClient';

const CONNECTION_STATUS = {
  CONNECTED: 'connected',
  DISCONNECTED: 'disconnected',
  ERROR: 'error',
};

export const useRoomsSocket = ({
  currentUser,
  setConnectionStatus,
  setRooms,
}) => {
  const socketRef = useRef(null);

  useEffect(() => {
    if (!currentUser?.token) return;

    let isActive = true;
    let activeSocket = null;
    let activeHandlers = null;

    const connectSocket = async () => {
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
          },
          disconnect: () => {
            setConnectionStatus(CONNECTION_STATUS.DISCONNECTED);
          },
          error: () => {
            setConnectionStatus(CONNECTION_STATUS.ERROR);
          },
          roomCreated: (newRoom) => {
            setRooms((prev) => [newRoom, ...prev]);
          },
          roomUpdated: (updatedRoom) => {
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

        // connect()는 최초 connect 이벤트가 끝난 뒤 resolve되므로 즉시 구독한다.
        // 이후 재연결에서는 위 connect 핸들러가 다시 구독한다.
        setConnectionStatus(CONNECTION_STATUS.CONNECTED);
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
