'use client';

import { useEffect } from 'react';

const CLIENT_LOG_ENDPOINT = '/api/client-logs';
const CLIENT_LOG_INSTALLED = '__ktb_client_log_bridge_installed__';
const MAX_ARG_LENGTH = 500;

function serializeArg(arg) {
  if (typeof arg === 'string') {
    return arg.slice(0, MAX_ARG_LENGTH);
  }

  if (arg instanceof Error) {
    return {
      name: arg.name,
      message: arg.message,
      stack: arg.stack ? arg.stack.slice(0, MAX_ARG_LENGTH) : undefined
    };
  }

  try {
    return JSON.parse(JSON.stringify(arg));
  } catch {
    return String(arg).slice(0, MAX_ARG_LENGTH);
  }
}

function sendClientLog(payload) {
  const body = JSON.stringify(payload);

  if (navigator.sendBeacon) {
    const blob = new Blob([body], { type: 'application/json' });
    navigator.sendBeacon(CLIENT_LOG_ENDPOINT, blob);
    return;
  }

  fetch(CLIENT_LOG_ENDPOINT, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body,
    keepalive: true
  }).catch(() => {
    // 클라이언트 로그 미러링 실패는 앱 동작에 영향을 주지 않는다.
  });
}

export default function ClientLogBridge() {
  useEffect(() => {
    if (typeof window === 'undefined' || window[CLIENT_LOG_INSTALLED]) {
      return;
    }

    const originalInfo = console.info.bind(console);

    window[CLIENT_LOG_INSTALLED] = true;

    console.info = (...args) => {
      originalInfo(...args);

      try {
        sendClientLog({
          level: 'info',
          path: window.location.pathname + window.location.search,
          args: args.map(serializeArg),
          timestamp: new Date().toISOString()
        });
      } catch {
        // 로깅 실패는 무시한다.
      }
    };

    return () => {
      console.info = originalInfo;
      delete window[CLIENT_LOG_INSTALLED];
    };
  }, []);

  return null;
}
