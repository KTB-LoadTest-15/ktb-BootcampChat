import { useMemo, useReducer, useRef } from 'react';

export const createInitialChatRoomState = () => ({
  room: null,
  messages: [],
  currentUser: null,
  error: '',
  loading: true,
  connectionStatus: 'checking',
  messageLoadError: null,
  isInitialized: false,
  hasMoreMessages: true,
  loadingMessages: false,
  // 방 참가자들의 읽음 커서(userId → lastReadTs epoch millis). read cursor 방식.
  readCursors: {},
});

const resolveValue = (value, currentValue) => (
  typeof value === 'function' ? value(currentValue) : value
);

export const chatRoomReducer = (state, action) => {
  switch (action.type) {
    case 'room/setupStarted':
      return {
        ...state,
        loading: true,
        error: null,
      };
    case 'room/setupSucceeded':
      return {
        ...state,
        room: action.room,
        isInitialized: true,
        loading: false,
      };
    case 'room/setupFailed':
      return {
        ...state,
        error: action.error,
        loading: false,
      };
    case 'room/cleanupManual':
      return {
        ...state,
        error: null,
        loading: false,
        loadingMessages: false,
        messages: [],
        readCursors: {},
      };
    case 'room/changed':
      {
        const room = resolveValue(action.room, state.room);
        if (room === state.room) return state;
        return {
          ...state,
          room,
        };
      }
    case 'messages/changed':
      {
        const messages = resolveValue(action.messages, state.messages);
        if (messages === state.messages) return state;
        return {
          ...state,
          messages,
        };
      }
    case 'readCursors/changed':
      {
        const readCursors = resolveValue(action.readCursors, state.readCursors);
        if (readCursors === state.readCursors) return state;
        return {
          ...state,
          readCursors,
        };
      }
    case 'error/changed':
      {
        const error = resolveValue(action.error, state.error);
        if (error === state.error) return state;
        return {
          ...state,
          error,
        };
      }
    case 'connection/established':
      return {
        ...state,
        connectionStatus: 'connected',
      };
    case 'connection/lost':
      return {
        ...state,
        connectionStatus: 'disconnected',
      };
    case 'connection/failed':
      return {
        ...state,
        connectionStatus: 'error',
        error: action.error,
      };
    case 'connection/reconnecting':
      return {
        ...state,
        connectionStatus: 'connecting',
      };
    case 'connection/recovered':
      return {
        ...state,
        connectionStatus: 'connected',
        error: '',
      };
    case 'messages/loadingChanged':
      if (action.loadingMessages === state.loadingMessages) return state;
      return {
        ...state,
        loadingMessages: action.loadingMessages,
      };
    case 'messages/hasMoreChanged':
      if (action.hasMoreMessages === state.hasMoreMessages) return state;
      return {
        ...state,
        hasMoreMessages: action.hasMoreMessages,
      };
    case 'user/currentChanged':
      if (action.currentUser === state.currentUser) return state;
      return {
        ...state,
        currentUser: action.currentUser,
      };
    default:
      return state;
  }
};

export const useChatRoomState = () => {
  const [state, dispatch] = useReducer(
    chatRoomReducer,
    undefined,
    createInitialChatRoomState,
  );

  const refs = {
    messageLoadAttemptRef: useRef(0),
    mountedRef: useRef(true),
    initializingRef: useRef(false),
    setupCompleteRef: useRef(false),
    cleanupInProgressRef: useRef(false),
    userRooms: useRef(new Map()),
    previousMessagesRef: useRef(new Set()),
    messageProcessingRef: useRef(false),
    initialLoadCompletedRef: useRef(false),
    processedMessageIds: useRef(new Set()),
    loadMoreTimeoutRef: useRef(null),
  };

  const actions = useMemo(() => ({
    setupStarted: () => dispatch({ type: 'room/setupStarted' }),
    setupSucceeded: room => dispatch({ type: 'room/setupSucceeded', room }),
    setupFailed: error => dispatch({ type: 'room/setupFailed', error }),
    cleanupManual: () => dispatch({ type: 'room/cleanupManual' }),
    setRoom: room => dispatch({ type: 'room/changed', room }),
    setMessages: messages => dispatch({ type: 'messages/changed', messages }),
    setReadCursors: readCursors => dispatch({ type: 'readCursors/changed', readCursors }),
    setError: error => dispatch({ type: 'error/changed', error }),
    setLoadingMessages: value => dispatch({
      type: 'messages/loadingChanged',
      loadingMessages: value,
    }),
    setHasMoreMessages: value => dispatch({
      type: 'messages/hasMoreChanged',
      hasMoreMessages: value,
    }),
    connectionEstablished: () => dispatch({ type: 'connection/established' }),
    connectionLost: () => dispatch({ type: 'connection/lost' }),
    connectionFailed: error => dispatch({ type: 'connection/failed', error }),
    connectionReconnecting: () => dispatch({ type: 'connection/reconnecting' }),
    connectionRecovered: () => dispatch({ type: 'connection/recovered' }),
    setCurrentUser: currentUser => dispatch({ type: 'user/currentChanged', currentUser }),
  }), []);

  return {
    state,
    refs,
    actions,
  };
};

export default useChatRoomState;
