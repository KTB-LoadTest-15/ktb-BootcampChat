import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Toast } from '@/components/Toast';
import socketClient from '@/lib/socket/socketClient';
import { useReactionHandling } from '../useReactionHandling';

vi.mock('@/lib/socket/socketClient', () => ({
  default: {
    canSend: vi.fn(() => true),
    sendMessageReaction: vi.fn(),
  },
}));

vi.mock('@/components/Toast', () => ({
  Toast: { error: vi.fn() },
}));

const currentUser = { id: 'user-1' };
const messages = [{ _id: 'message-1', reactions: { '👍': ['user-1'] } }];

describe('useReactionHandling', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    socketClient.canSend.mockReturnValue(true);
  });

  it('delegates reaction add to socketClient', async () => {
    const setMessages = vi.fn();
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, messages, setMessages })
    );

    await act(async () => {
      await result.current.handleReactionAdd('message-1', '👍');
    });

    expect(socketClient.sendMessageReaction).toHaveBeenCalledWith(
      'message-1',
      '👍',
      'add',
    );
  });

  it('delegates reaction remove to socketClient', async () => {
    const setMessages = vi.fn();
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, messages, setMessages })
    );

    await act(async () => {
      await result.current.handleReactionRemove('message-1', '👍');
    });

    expect(socketClient.sendMessageReaction).toHaveBeenCalledWith(
      'message-1',
      '👍',
      'remove',
    );
  });

  it('does not send reaction add when the socket client cannot send', async () => {
    const setMessages = vi.fn();
    socketClient.canSend.mockReturnValue(false);
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, messages, setMessages })
    );

    await act(async () => {
      await result.current.handleReactionAdd('message-1', '👍');
    });

    expect(socketClient.sendMessageReaction).not.toHaveBeenCalled();
    expect(Toast.error).toHaveBeenCalledWith('리액션 추가에 실패했습니다.');
  });

  it('does not send reaction remove when the socket client cannot send', async () => {
    const setMessages = vi.fn();
    socketClient.canSend.mockReturnValue(false);
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, messages, setMessages })
    );

    await act(async () => {
      await result.current.handleReactionRemove('message-1', '👍');
    });

    expect(socketClient.sendMessageReaction).not.toHaveBeenCalled();
    expect(Toast.error).toHaveBeenCalledWith('리액션 제거에 실패했습니다.');
  });

  it('keeps reaction callbacks stable when messages change', () => {
    const setMessages = vi.fn();
    const { result, rerender } = renderHook(
      ({ user, currentMessages }) =>
        useReactionHandling({
          currentUser: user,
          messages: currentMessages,
          setMessages,
        }),
      {
        initialProps: {
          user: currentUser,
          currentMessages: messages,
        },
      }
    );
    const initialAdd = result.current.handleReactionAdd;
    const initialRemove = result.current.handleReactionRemove;

    rerender({
      user: { ...currentUser },
      currentMessages: [...messages, { _id: 'message-2', reactions: {} }],
    });

    expect(result.current.handleReactionAdd).toBe(initialAdd);
    expect(result.current.handleReactionRemove).toBe(initialRemove);
  });

  it('rolls back only the current user reaction when add fails', async () => {
    let currentMessages = [{
      _id: 'message-1',
      reactions: { '👍': ['user-2'] },
    }];
    const setMessages = vi.fn(updater => {
      currentMessages = updater(currentMessages);
    });
    socketClient.sendMessageReaction.mockImplementation(async () => {
      currentMessages = currentMessages.map(message => ({
        ...message,
        reactions: { '👍': [...message.reactions['👍'], 'user-3'] },
      }));
      throw new Error('send failed');
    });
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, setMessages })
    );

    await act(async () => {
      await result.current.handleReactionAdd('message-1', '👍');
    });

    expect(currentMessages[0].reactions['👍']).toEqual(['user-2', 'user-3']);
  });

  it('restores only the current user reaction when remove fails', async () => {
    let currentMessages = [{
      _id: 'message-1',
      reactions: { '👍': ['user-1', 'user-2'] },
    }];
    const setMessages = vi.fn(updater => {
      currentMessages = updater(currentMessages);
    });
    socketClient.sendMessageReaction.mockImplementation(async () => {
      currentMessages = currentMessages.map(message => ({
        ...message,
        reactions: { '👍': [...message.reactions['👍'], 'user-3'] },
      }));
      throw new Error('send failed');
    });
    const { result } = renderHook(() =>
      useReactionHandling({ currentUser, setMessages })
    );

    await act(async () => {
      await result.current.handleReactionRemove('message-1', '👍');
    });

    expect(currentMessages[0].reactions['👍']).toEqual(['user-2', 'user-3', 'user-1']);
  });
});
