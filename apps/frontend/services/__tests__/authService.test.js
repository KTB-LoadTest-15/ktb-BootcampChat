import { beforeEach, describe, expect, it, vi } from 'vitest';

const { apiPost } = vi.hoisted(() => ({
  apiPost: vi.fn(),
}));

vi.mock('../../lib/api/client', () => ({
  default: { post: apiPost },
  getAuthHeaders: vi.fn(() => ({})),
  HEALTH_TIMEOUT_MS: 3000,
}));

vi.mock('../../lib/auth/authStorage', () => ({
  loadStoredUser: vi.fn(() => null),
}));

import authService from '../authService';

describe('authService load behavior', () => {
  beforeEach(() => {
    apiPost.mockReset();
  });

  it('does not automatically retry session-creating requests', async () => {
    apiPost
      .mockResolvedValueOnce({
        data: {
          success: true,
          token: 'token-1',
          sessionId: 'session-1',
          user: {
            _id: 'user-1',
            name: 'Test User',
            email: 'test@example.com',
          },
        },
      })
      .mockResolvedValueOnce({ data: { success: true } });

    await authService.login({ email: 'test@example.com', password: 'password' });
    await authService.register({
      name: 'Test User',
      email: 'test@example.com',
      password: 'password',
    });

    expect(apiPost).toHaveBeenNthCalledWith(
      1,
      '/api/auth/login',
      { email: 'test@example.com', password: 'password' },
      expect.objectContaining({ maxRetries: 0 })
    );
    expect(apiPost).toHaveBeenNthCalledWith(
      2,
      '/api/auth/register',
      expect.any(Object),
      { maxRetries: 0 }
    );
  });

  it('keeps a 429 response distinguishable from a network failure', async () => {
    apiPost.mockRejectedValue({
      status: 429,
      message: '너무 많은 요청이 발생했습니다.',
    });

    await expect(
      authService.login({ email: 'test@example.com', password: 'password' })
    ).rejects.toThrow('너무 많은 로그인 시도가 있었습니다. 잠시 후 다시 시도해주세요.');
  });
});
