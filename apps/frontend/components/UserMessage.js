import React from 'react';
import { VStack, HStack } from '@vapor-ui/core';
import MessageContent from './MessageContent';
import MessageActions from './MessageActions';
import CustomAvatar from './CustomAvatar';
import ReadStatus from './ReadStatus';

const UserMessage = ({
  msg = {},
  isMine = false,
  currentUser = null,
  unreadCount = 0,
  participantNamesById,
  onReactionAdd,
  onReactionRemove,
}) => {
  const readableTimestamp = typeof msg.timestamp === 'number'
    ? msg.timestamp
    : Date.parse(msg.timestamp);

  const formattedTime = new Date(msg.timestamp).toLocaleString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  }).replace(/\./g, '년').replace(/\s/g, ' ').replace('일 ', '일 ');

  const user = isMine ? currentUser : msg.sender;

  return (
    <div
      className="my-4"
      data-testid="message-container"
      data-readable-message-id={msg._id}
      data-readable-message-timestamp={Number.isFinite(readableTimestamp) ? readableTimestamp : undefined}
    >
      <VStack
        className={`max-w-[65%] ${isMine ? 'ml-auto items-end' : 'mr-auto items-start'}`}
        align={isMine ? 'flex-end' : 'flex-start'}
        $css={{ gap: '$100' }}
      >
        {/* Sender Info */}
        <HStack className="px-1" $css={{ gap: '$100', alignItems: 'center' }}>
          <CustomAvatar
            user={user}
            size="lg"
            persistent
            showInitials
          />
          <span className="text-sm font-medium text-gray-300">
            {isMine ? '나' : msg.sender?.name}
          </span>
        </HStack>

        {/* Message Bubble - Outline Based */}
        <div className={`
          relative group
          rounded-2xl px-4 py-3
          border transition-all duration-200
          ${isMine
            ? 'bg-gray-800 border-blue-500 hover:border-blue-400 hover:shadow-md'
            : 'bg-transparent border-gray-400 hover:border-gray-300 hover:shadow-md'
          }
        `}>
          {/* Message Content */}
          <div className={`
            text-base leading-relaxed
            ${isMine ? 'text-blue-100' : 'text-white'}
          `}
            data-testid="message-content"
          >
            <MessageContent content={msg.content} />
          </div>

          {/* Message Footer */}
          <HStack
            $css={{
              gap: '$150',
              justifyContent: 'flex-end',
              alignItems: 'center',
            }}
            className={`mt-2 pt-2 border-t ${isMine ? 'border-gray-700' : 'border-gray-600'}`}
          >
            <div className={`text-xs ${isMine ? 'text-blue-400' : 'text-gray-300'}`}>
              {formattedTime}
            </div>
            <ReadStatus
              messageType={msg.type}
              unreadCount={unreadCount}
            />
          </HStack>
        </div>

        {/* Message Actions */}
        <MessageActions
          messageId={msg._id}
          messageContent={msg.content}
          reactions={msg.reactions}
          currentUserId={currentUser?._id || currentUser?.id}
          onReactionAdd={onReactionAdd}
          onReactionRemove={onReactionRemove}
          isMine={isMine}
          participantNamesById={participantNamesById}
        />
      </VStack>
    </div>
  );
};

UserMessage.defaultProps = {
  msg: {},
  isMine: false,
  currentUser: null,
  onReactionAdd: () => {},
  onReactionRemove: () => {},
  unreadCount: 0,
  participantNamesById: null,
};

export default React.memo(UserMessage);
