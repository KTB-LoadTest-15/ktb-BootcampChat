import { describe, expect, it } from 'vitest';
import {
  collectUniqueMessages,
  deriveUniqueSortedMessages,
  mergeSortedMessages,
  mergeUniqueSortedMessages,
} from '../useMessageList';

describe('mergeUniqueSortedMessages', () => {
  it('appends unseen messages in timestamp order without mutating processed ids', () => {
    const processedIds = new Set(['existing']);

    const result = deriveUniqueSortedMessages(
      [{ _id: 'existing', content: 'middle', timestamp: '2026-01-01T00:00:02Z' }],
      [
        { _id: 'late', content: 'late', timestamp: '2026-01-01T00:00:03Z' },
        { _id: 'early', content: 'early', timestamp: '2026-01-01T00:00:01Z' },
      ],
      processedIds
    );

    expect(result.messages.map((message) => message._id)).toEqual([
      'early',
      'existing',
      'late',
    ]);
    expect(processedIds).toEqual(new Set(['existing']));
    expect(result.processedMessageIds).toEqual(
      new Set(['existing', 'late', 'early'])
    );
  });

  it('ignores incoming duplicates and messages without ids', () => {
    const processedIds = new Set(['duplicate']);

    const messages = mergeUniqueSortedMessages(
      [{ _id: 'duplicate', content: 'original', timestamp: '2026-01-01T00:00:01Z' }],
      [
        { _id: 'duplicate', content: 'newer duplicate', timestamp: '2026-01-01T00:00:02Z' },
        { content: 'missing id', timestamp: '2026-01-01T00:00:03Z' },
      ],
      processedIds
    );

    expect(messages).toEqual([
      { _id: 'duplicate', content: 'original', timestamp: '2026-01-01T00:00:01Z' },
    ]);
  });

  it('throws for invalid incoming message payloads', () => {
    expect(() => mergeUniqueSortedMessages([], null, new Set())).toThrow(
      'Invalid messages format'
    );
  });

  it('returns stable results when called twice with the same inputs', () => {
    const processedIds = new Set(['existing']);
    const currentMessages = [
      { _id: 'existing', content: 'old', timestamp: '2026-01-01T00:00:01Z' },
    ];
    const incomingMessages = [
      { _id: 'next', content: 'new', timestamp: '2026-01-01T00:00:02Z' },
    ];

    const first = deriveUniqueSortedMessages(
      currentMessages,
      incomingMessages,
      processedIds
    );
    const second = deriveUniqueSortedMessages(
      currentMessages,
      incomingMessages,
      processedIds
    );

    expect(second.messages).toEqual(first.messages);
    expect(second.processedMessageIds).toEqual(first.processedMessageIds);
    expect(processedIds).toEqual(new Set(['existing']));
  });

  it('prepends an older sorted page while preserving current message objects', () => {
    const currentMessages = [
      { _id: 'current-1', timestamp: '2026-01-01T00:00:03Z' },
      { _id: 'current-2', timestamp: '2026-01-01T00:00:04Z' },
    ];
    const incomingMessages = [
      { _id: 'history-1', timestamp: '2026-01-01T00:00:01Z' },
      { _id: 'history-2', timestamp: '2026-01-01T00:00:02Z' },
    ];

    const result = mergeSortedMessages(currentMessages, incomingMessages);

    expect(result.map(message => message._id)).toEqual([
      'history-1',
      'history-2',
      'current-1',
      'current-2',
    ]);
    expect(result[2]).toBe(currentMessages[0]);
    expect(result[3]).toBe(currentMessages[1]);
  });

  it('linearly merges pages whose timestamp ranges overlap', () => {
    const result = mergeSortedMessages(
      [
        { _id: 'message-1', timestamp: '2026-01-01T00:00:01Z' },
        { _id: 'message-3', timestamp: '2026-01-01T00:00:03Z' },
      ],
      [
        { _id: 'message-4', timestamp: '2026-01-01T00:00:04Z' },
        { _id: 'message-2', timestamp: '2026-01-01T00:00:02Z' },
      ]
    );

    expect(result.map(message => message._id)).toEqual([
      'message-1',
      'message-2',
      'message-3',
      'message-4',
    ]);
  });

  it('collects a history page once without mutating the processed id set', () => {
    const processedIds = new Set(['existing']);
    const result = collectUniqueMessages(
      [
        { _id: 'existing' },
        { _id: 'history-1' },
        { _id: 'history-1' },
        { content: 'missing id' },
      ],
      processedIds
    );

    expect(result.messages.map(message => message._id)).toEqual(['history-1']);
    expect(result.processedMessageIds).toEqual(
      new Set(['existing', 'history-1'])
    );
    expect(processedIds).toEqual(new Set(['existing']));
  });

  it('keeps the current array reference when a page has no new messages', () => {
    const currentMessages = [
      { _id: 'existing', timestamp: '2026-01-01T00:00:01Z' },
    ];
    const processedIds = new Set(['existing']);

    const result = deriveUniqueSortedMessages(
      currentMessages,
      [{ _id: 'existing', timestamp: '2026-01-01T00:00:01Z' }],
      processedIds
    );

    expect(result.messages).toBe(currentMessages);
    expect(result.processedMessageIds).toBe(processedIds);
  });
});
