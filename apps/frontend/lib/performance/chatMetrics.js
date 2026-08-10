const METRICS_KEY = '__KTB_PERF_METRICS__';

const getMetrics = () => {
  if (typeof window === 'undefined') return null;

  const metrics = window[METRICS_KEY];
  return metrics?.enabled ? metrics : null;
};

export const recordIncomingMessage = (messageId) => {
  const metrics = getMetrics();
  if (!metrics || !messageId) return;

  metrics.messagesReceived += 1;
  metrics.messageReceivedAt[messageId] = performance.now();
};

export const recordMessageDomDisplayed = (messageId) => {
  const metrics = getMetrics();
  if (!metrics || !messageId) return;

  const receivedAt = metrics.messageReceivedAt[messageId];
  if (typeof receivedAt !== 'number') return;

  metrics.messageToDomMs.push(performance.now() - receivedAt);
  metrics.messagesDomDisplayed += 1;
  delete metrics.messageReceivedAt[messageId];
};

export const recordUserMessageRender = (messageId) => {
  const metrics = getMetrics();
  if (!metrics || !messageId) return;

  metrics.userMessageRenders[messageId] =
    (metrics.userMessageRenders[messageId] || 0) + 1;
};

export const recordReadReceiptSent = (messageIds) => {
  const metrics = getMetrics();
  if (!metrics || !Array.isArray(messageIds)) return;

  metrics.readSentEvents += 1;
  metrics.readSentMessageIds += messageIds.length;
};

export const recordReadReceiptReceived = (messageIds) => {
  const metrics = getMetrics();
  if (!metrics || !Array.isArray(messageIds)) return;

  metrics.readReceivedEvents += 1;
  metrics.readReceivedMessageIds += messageIds.length;
};

export { METRICS_KEY };
