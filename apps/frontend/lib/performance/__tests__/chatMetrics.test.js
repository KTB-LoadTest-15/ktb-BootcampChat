import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  METRICS_KEY,
  recordIncomingMessage,
  recordMessageDomDisplayed,
  recordReadReceiptReceived,
  recordReadReceiptSent,
  recordUserMessageRender,
} from '../chatMetrics';

const createMetrics = () => ({
  enabled: true,
  messageReceivedAt: {},
  messageToDomMs: [],
  messagesReceived: 0,
  messagesDomDisplayed: 0,
  userMessageRenders: {},
  readSentEvents: 0,
  readSentMessageIds: 0,
  readReceivedEvents: 0,
  readReceivedMessageIds: 0,
});

describe('chatMetrics', () => {
  beforeEach(() => {
    window[METRICS_KEY] = createMetrics();
  });

  afterEach(() => {
    delete window[METRICS_KEY];
    vi.restoreAllMocks();
  });

  it('measures the delay from a socket message to its DOM display', () => {
    vi.spyOn(performance, 'now')
      .mockReturnValueOnce(100)
      .mockReturnValueOnce(145.5);

    recordIncomingMessage('message-1');
    recordMessageDomDisplayed('message-1');

    expect(window[METRICS_KEY].messagesReceived).toBe(1);
    expect(window[METRICS_KEY].messagesDomDisplayed).toBe(1);
    expect(window[METRICS_KEY].messageToDomMs).toEqual([45.5]);
    expect(window[METRICS_KEY].messageReceivedAt).not.toHaveProperty('message-1');
  });

  it('counts renders per message and read receipt event fan-out', () => {
    recordUserMessageRender('message-1');
    recordUserMessageRender('message-1');
    recordUserMessageRender('message-2');
    recordReadReceiptSent(['message-1', 'message-2']);
    recordReadReceiptReceived(['message-1']);

    expect(window[METRICS_KEY]).toMatchObject({
      userMessageRenders: {
        'message-1': 2,
        'message-2': 1,
      },
      readSentEvents: 1,
      readSentMessageIds: 2,
      readReceivedEvents: 1,
      readReceivedMessageIds: 1,
    });
  });

  it('is a no-op outside an instrumented browser', () => {
    delete window[METRICS_KEY];

    expect(() => {
      recordIncomingMessage('message-1');
      recordMessageDomDisplayed('message-1');
      recordUserMessageRender('message-1');
      recordReadReceiptSent(['message-1']);
      recordReadReceiptReceived(['message-1']);
    }).not.toThrow();
  });
});
