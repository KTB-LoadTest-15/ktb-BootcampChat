import axios from 'axios';
import { clearAuthStorage, loadStoredUser } from '../auth/authStorage';
import {
  createAuthExpiredError,
  createHttpError,
  createNetworkError,
  getRetryDelay,
  isCanceledRequest,
  isRetryableError,
  RETRY_CONFIG,
} from './errors';

export const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:5000';

/** 서버 생존 확인은 공통 타임아웃보다 짧게 끊는다. 호출하는 화면마다 값이 갈리지 않도록 여기서 정한다. */
export const HEALTH_TIMEOUT_MS = 3000;

export const getAuthHeaders = (session = loadStoredUser()) => {
  if (!session?.token) {
    return {};
  }

  return {
    'x-auth-token': session.token,
    ...(session.sessionId ? { 'x-session-id': session.sessionId } : {}),
  };
};

export const createApiClient = ({
  baseURL = API_BASE_URL,
  getSession = loadStoredUser,
} = {}) => {
  const axiosInstance = axios.create({
    baseURL,
    timeout: 5000,
    withCredentials: true,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
  });

  axiosInstance.interceptors.request.use(
    (config) => {
      if (config.method !== 'get' && !config.data) {
        config.data = {};
      }

      if (!config.skipAuth) {
        config.headers = {
          ...config.headers,
          ...getAuthHeaders(getSession()),
        };
      }

      return config;
    },
    (error) => Promise.reject(error)
  );

  axiosInstance.interceptors.response.use(
    (response) => response,
    async (error) => {
      const config = error.config || {};
      config.retryCount = config.retryCount || 0;
      const maxRetries = Number.isInteger(config.maxRetries)
        ? Math.max(0, config.maxRetries)
        : RETRY_CONFIG.maxRetries;

      if (isCanceledRequest(error)) {
        return Promise.reject(error);
      }

      if (error.response?.status === 401 && config.handleAuthError !== false) {
        clearAuthStorage();

        if (
          typeof window !== 'undefined' &&
          window.location.pathname !== '/' &&
          window.location.pathname !== '/login'
        ) {
          window.location.href = '/';
        }

        throw createAuthExpiredError(error, config);
      }

      if (error.response?.status === 401 && config.handleAuthError === false) {
        return Promise.reject(error);
      }

      if (isRetryableError(error) && config.retryCount < maxRetries) {
        config.retryCount++;
        const delay = getRetryDelay(config.retryCount);

        try {
          await new Promise((resolve) => setTimeout(resolve, delay));
          return await axiosInstance(config);
        } catch (retryError) {
          return Promise.reject(retryError);
        }
      }

      const retry = async () => axiosInstance(config);

      if (!error.response) {
        throw createNetworkError(error, config, retry);
      }

      throw createHttpError(error, config, retry);
    }
  );

  return axiosInstance;
};

const apiClient = createApiClient();

export default apiClient;
