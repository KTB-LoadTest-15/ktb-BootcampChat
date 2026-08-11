import socketClient from './socketClient';

export const ensureSocketReady = async ({
  currentUser,
  attemptConnection,
} = {}) => {
  if (!currentUser?.token || !currentUser?.sessionId) {
    throw new Error('인증 정보가 유효하지 않습니다.');
  }

  if (typeof attemptConnection === 'function') {
    await attemptConnection();
  }

  return socketClient.connect({
    auth: {
      token: currentUser.token,
      sessionId: currentUser.sessionId,
    },
  });
};

export default ensureSocketReady;
