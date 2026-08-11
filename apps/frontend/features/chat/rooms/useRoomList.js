import { useState, useCallback, useRef } from 'react';
import axiosInstance from '@/services/axios';
import { CONNECTION_STATUS } from './useServerConnection';

export const ROOM_JOIN_METRIC_PREFIX = '[room-join-metric]';
const ROOM_JOIN_NAVIGATION_TIMEOUT_MS = 5000;
const ROOM_JOIN_NAVIGATION_POLL_MS = 50;

const now = () => (
  typeof performance !== 'undefined' ? performance.now() : Date.now()
);

const currentPathname = () => (
  typeof window !== 'undefined' ? window.location.pathname : null
);

const createTraceId = () => {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }

  return `room-join-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
};

const logRoomJoinMetric = (event, details) => {
  const payload = {
    event,
    recordedAt: new Date().toISOString(),
    ...details,
  };

  // JSON 한 줄로 남겨야 Playwright/Artillery가 브라우저 콘솔에서 그대로 수집할 수 있다.
  console.info(`${ROOM_JOIN_METRIC_PREFIX} ${JSON.stringify(payload)}`);
};

const observeNavigation = ({ traceId, roomId, startedAt }) => {
  if (typeof window === 'undefined') return;

  const targetPath = `/chat/${roomId}`;

  const checkPath = () => {
    const durationMs = Math.round(now() - startedAt);
    const pathname = currentPathname();

    if (pathname === targetPath) {
      logRoomJoinMetric('navigation_complete', {
        traceId,
        roomId,
        targetPath,
        pathname,
        durationMs,
      });
      return;
    }

    if (durationMs >= ROOM_JOIN_NAVIGATION_TIMEOUT_MS) {
      logRoomJoinMetric('navigation_timeout', {
        traceId,
        roomId,
        targetPath,
        pathname,
        durationMs,
      });
      return;
    }

    window.setTimeout(checkPath, ROOM_JOIN_NAVIGATION_POLL_MS);
  };

  window.setTimeout(checkPath, 0);
};

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

  const loadRooms = useCallback(({ staleAfterMs = 0, maxRetries } = {}) => {
    if (pendingRequestRef.current) {
      return pendingRequestRef.current;
    }

    if (
      staleAfterMs > 0 &&
      Date.now() - lastSuccessfulFetchAtRef.current < staleAfterMs
    ) {
      return Promise.resolve({ skipped: true });
    }

    const request = (
      maxRetries === undefined
        ? axiosInstance.get('/api/rooms')
        : axiosInstance.get('/api/rooms', { maxRetries })
    )
      .then((response) => {
        if (!response?.data?.data) {
          throw new Error('INVALID_RESPONSE');
        }

        setRooms(response.data.data);
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
    const traceId = createTraceId();
    const attemptStartedAt = now();

    logRoomJoinMetric('attempt', {
      traceId,
      roomId,
      connectionStatus,
      pathname: currentPathname(),
    });

    if (connectionStatus !== CONNECTION_STATUS.CONNECTED) {
      logRoomJoinMetric('connection_blocked', {
        traceId,
        roomId,
        connectionStatus,
        pathname: currentPathname(),
        durationMs: Math.round(now() - attemptStartedAt),
      });

      setError({
        title: '채팅방 입장 실패',
        message: '서버와 연결이 끊어져 있습니다.',
        type: 'danger',
      });
      return;
    }

    setJoiningRoom(true);

    try {
      const requestStartedAt = now();
      logRoomJoinMetric('request_start', {
        traceId,
        roomId,
        pathname: currentPathname(),
      });

      const response = await axiosInstance.post(`/api/rooms/${roomId}/join`, {});
      const requestDurationMs = Math.round(now() - requestStartedAt);

      logRoomJoinMetric('request_complete', {
        traceId,
        roomId,
        status: response.status,
        success: Boolean(response.data?.success),
        retryCount: response.config?.retryCount || 0,
        durationMs: requestDurationMs,
      });

      if (response.data.success) {
        const navigationStartedAt = now();
        const targetPath = `/chat/${roomId}`;

        logRoomJoinMetric('navigation_start', {
          traceId,
          roomId,
          targetPath,
          pathname: currentPathname(),
          elapsedSinceAttemptMs: Math.round(navigationStartedAt - attemptStartedAt),
        });

        const navigationResult = router.push(targetPath);
        if (navigationResult?.catch) {
          navigationResult.catch((navigationError) => {
            logRoomJoinMetric('navigation_error', {
              traceId,
              roomId,
              targetPath,
              pathname: currentPathname(),
              durationMs: Math.round(now() - navigationStartedAt),
              message: navigationError?.message || 'Unknown navigation error',
            });
          });
        }

        observeNavigation({
          traceId,
          roomId,
          startedAt: navigationStartedAt,
        });
      } else {
        logRoomJoinMetric('request_rejected', {
          traceId,
          roomId,
          status: response.status,
          retryCount: response.config?.retryCount || 0,
          durationMs: requestDurationMs,
        });
      }
    } catch (error) {
      logRoomJoinMetric('request_error', {
        traceId,
        roomId,
        status: error.status || error.response?.status || 0,
        retryCount: error.config?.retryCount || 0,
        durationMs: Math.round(now() - attemptStartedAt),
        code: error.code || error.response?.data?.code || null,
        message: error.message || 'Unknown room join error',
      });

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
    fetchRooms,
    refreshRooms,
    handleJoinRoom,
  };
};

export default useRoomList;
