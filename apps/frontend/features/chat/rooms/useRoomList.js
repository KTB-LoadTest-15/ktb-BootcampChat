import { useState, useCallback, useRef } from 'react';
import axiosInstance from '@/services/axios';
import { CONNECTION_STATUS } from './useServerConnection';

export const useRoomList = ({
  currentUser,
  router,
  connectionStatus,
  setConnectionStatus,
}) => {
  const [rooms, setRooms] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [isInitialLoad, setIsInitialLoad] = useState(true);
  const [joiningRoom, setJoiningRoom] = useState(false);
  const [nextCursor, setNextCursor] = useState(null);
  const [loadingMore, setLoadingMore] = useState(false);

  const pendingRequestRef = useRef(null);
  const lastSuccessfulFetchAtRef = useRef(0);

  const handleFetchError = useCallback((error) => {
    let errorMessage = '채팅방 목록을 불러오는데 실패했습니다.';
    let errorType = 'danger';
    let showRetry = true;

    if (error.code === 'AUTH_EXPIRED' || error.message === 'AUTH_EXPIRED') {
      errorMessage = '인증이 만료되었습니다. 다시 로그인해주세요.';
      errorType = 'danger';
      showRetry = false;

      setError({
        title: '인증 만료',
        message: errorMessage,
        type: errorType,
        showRetry,
      });

      setConnectionStatus(CONNECTION_STATUS.ERROR);
      return;
    }

    if (error.isNetworkError || error.message === 'SERVER_UNREACHABLE') {
      errorMessage = '서버와 연결할 수 없습니다. 다시 시도해주세요.';
      errorType = 'warning';
      showRetry = true;
    }

    setError({
      title: '채팅방 목록 로드 실패',
      message: errorMessage,
      type: errorType,
      showRetry,
    });
  }, [setConnectionStatus]);

  const loadRooms = useCallback(({
    staleAfterMs = 0,
    maxRetries,
    cursor = null,
    append = false,
  } = {}) => {
    if (pendingRequestRef.current) {
      return pendingRequestRef.current;
    }

    if (
      staleAfterMs > 0 &&
      Date.now() - lastSuccessfulFetchAtRef.current < staleAfterMs
    ) {
      return Promise.resolve({ skipped: true });
    }

    const requestConfig = {};
    if (maxRetries !== undefined) requestConfig.maxRetries = maxRetries;
    if (cursor) requestConfig.params = { cursor };

    const request = (Object.keys(requestConfig).length === 0
      ? axiosInstance.get('/api/rooms')
      : axiosInstance.get('/api/rooms', requestConfig))
      .then((response) => {
        if (!response?.data?.data) {
          throw new Error('INVALID_RESPONSE');
        }

        setRooms((previousRooms) => {
          if (!append) return response.data.data;
          const roomsById = new Map(previousRooms.map((room) => [room._id, room]));
          response.data.data.forEach((room) => roomsById.set(room._id, room));
          return Array.from(roomsById.values());
        });
        setNextCursor(response.data.metadata?.nextCursor || null);
        lastSuccessfulFetchAtRef.current = Date.now();
        return { skipped: false };
      })
      .finally(() => {
        if (pendingRequestRef.current === request) {
          pendingRequestRef.current = null;
        }
      });

    pendingRequestRef.current = request;
    return request;
  }, []);

  const loadMoreRooms = useCallback(async () => {
    if (!nextCursor || loadingMore) return false;
    try {
      setLoadingMore(true);
      await loadRooms({ cursor: nextCursor, append: true });
      return true;
    } catch (error) {
      handleFetchError(error);
      return false;
    } finally {
      setLoadingMore(false);
    }
  }, [nextCursor, loadingMore, loadRooms, handleFetchError]);

  const fetchRooms = useCallback(async () => {
    if (!currentUser?.token) {
      return false;
    }

    try {
      setLoading(true);
      setError(null);

      await loadRooms();

      if (isInitialLoad) {
        setIsInitialLoad(false);
      }

      return true;
    } catch (error) {
      handleFetchError(error);
      return false;
    } finally {
      setLoading(false);
    }
  }, [currentUser, isInitialLoad, loadRooms, handleFetchError]);

  /**
   * 이미 그려진 목록을 유지한 채 다시 조회한다.
   * 자동 갱신(silent)은 실패해도 화면을 흔들지 않고 다음 주기를 기다린다.
   */
  const refreshRooms = useCallback(async ({
    silent = false,
    staleAfterMs = 0,
    maxRetries,
  } = {}) => {
    if (!currentUser?.token) {
      return false;
    }

    try {
      if (!silent) {
        setRefreshing(true);
      }

      await loadRooms({ staleAfterMs, maxRetries });
      setError(null);

      return true;
    } catch (error) {
      if (!silent) {
        setError({
          title: '채팅방 목록 갱신 실패',
          message: '목록을 갱신하지 못했습니다. 잠시 후 다시 시도해주세요.',
          type: 'warning',
          showRetry: false,
        });
      }

      return false;
    } finally {
      if (!silent) {
        setRefreshing(false);
      }
    }
  }, [currentUser, loadRooms]);

  const handleJoinRoom = useCallback(async (roomId) => {
    if (connectionStatus !== CONNECTION_STATUS.CONNECTED) {
      setError({
        title: '채팅방 입장 실패',
        message: '서버와 연결이 끊어져 있습니다.',
        type: 'danger',
      });
      return;
    }

    setJoiningRoom(true);

    try {
      const response = await axiosInstance.post(`/api/rooms/${roomId}/join`, {});

      if (response.data.success) {
        router.push(`/chat/${roomId}`);
      }
    } catch (error) {
      let errorMessage = '입장에 실패했습니다.';
      if (error.response?.status === 404) {
        errorMessage = '채팅방을 찾을 수 없습니다.';
      } else if (error.response?.status === 403) {
        errorMessage = '채팅방 입장 권한이 없습니다.';
      }

      setError({
        title: '채팅방 입장 실패',
        message: error.response?.data?.message || errorMessage,
        type: 'danger',
      });
    } finally {
      setJoiningRoom(false);
    }
  }, [connectionStatus, router]);

  return {
    rooms,
    setRooms,
    error,
    setError,
    loading,
    refreshing,
    joiningRoom,
    loadingMore,
    hasMoreRooms: Boolean(nextCursor),
    fetchRooms,
    refreshRooms,
    loadMoreRooms,
    handleJoinRoom,
  };
};

export default useRoomList;
