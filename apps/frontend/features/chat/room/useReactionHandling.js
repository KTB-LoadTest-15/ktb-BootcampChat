import { useCallback } from 'react';
import { Toast } from '@/components/Toast';
import socketClient from '@/lib/socket/socketClient';

const updateMessageById = (messages, messageId, updateMessage) => {
  const messageIndex = messages.findIndex(message => message._id === messageId);
  if (messageIndex < 0) return messages;

  const currentMessage = messages[messageIndex];
  const updatedMessage = updateMessage(currentMessage);
  if (updatedMessage === currentMessage) return messages;

  const nextMessages = messages.slice();
  nextMessages[messageIndex] = updatedMessage;
  return nextMessages;
};

const addUserReaction = (messages, messageId, reaction, userId) =>
  updateMessageById(messages, messageId, msg => {
    const currentReactions = msg.reactions || {};
    const currentUsers = currentReactions[reaction] || [];
    if (currentUsers.includes(userId)) return msg;

    return {
      ...msg,
      reactions: {
        ...currentReactions,
        [reaction]: [...currentUsers, userId]
      }
    };
  });

const removeUserReaction = (messages, messageId, reaction, userId) =>
  updateMessageById(messages, messageId, msg => {
    const currentReactions = msg.reactions || {};
    const currentUsers = currentReactions[reaction] || [];
    if (!currentUsers.includes(userId)) return msg;

    return {
      ...msg,
      reactions: {
        ...currentReactions,
        [reaction]: currentUsers.filter(id => id !== userId)
      }
    };
  });

export const useReactionHandling = ({ currentUser, setMessages }) => {
  const currentUserId = currentUser?.id || currentUser?._id;

  const handleReactionAdd = useCallback(async (messageId, reaction) => {
    let optimisticUpdateApplied = false;

    try {
      if (!socketClient.canSend()) {
        throw new Error('Socket not connected');
      }

      // 낙관적 업데이트
      optimisticUpdateApplied = true;
      setMessages(prevMessages =>
        addUserReaction(prevMessages, messageId, reaction, currentUserId)
      );

      await socketClient.sendMessageReaction(messageId, reaction, 'add');

    } catch (error) {
      console.error('Add reaction error:', error);
      Toast.error('리액션 추가에 실패했습니다.');

      // 이 사용자의 낙관적 변경만 되돌려 다른 사용자의 최신 반응은 보존한다.
      if (optimisticUpdateApplied) {
        setMessages(prevMessages =>
          removeUserReaction(prevMessages, messageId, reaction, currentUserId)
        );
      }
    }
  }, [currentUserId, setMessages]);

  const handleReactionRemove = useCallback(async (messageId, reaction) => {
    let optimisticUpdateApplied = false;

    try {
      if (!socketClient.canSend()) {
        throw new Error('Socket not connected');
      }

      // 낙관적 업데이트
      optimisticUpdateApplied = true;
      setMessages(prevMessages =>
        removeUserReaction(prevMessages, messageId, reaction, currentUserId)
      );

      await socketClient.sendMessageReaction(messageId, reaction, 'remove');

    } catch (error) {
      console.error('Remove reaction error:', error);
      Toast.error('리액션 제거에 실패했습니다.');

      // 이 사용자의 낙관적 변경만 되돌려 다른 사용자의 최신 반응은 보존한다.
      if (optimisticUpdateApplied) {
        setMessages(prevMessages =>
          addUserReaction(prevMessages, messageId, reaction, currentUserId)
        );
      }
    }
  }, [currentUserId, setMessages]);

  const handleReactionUpdate = useCallback(({ messageId, reactions }) => {
    setMessages(prevMessages =>
      updateMessageById(prevMessages, messageId, msg => (
        msg.reactions === reactions ? msg : { ...msg, reactions }
      ))
    );
  }, [setMessages]);

  return {
    handleReactionAdd,
    handleReactionRemove,
    handleReactionUpdate
  };
};

export default useReactionHandling;
