const getMessageTimestamp = (message) => {
  const timestamp = message?.timestamp;
  if (typeof timestamp === 'number') return timestamp;
  if (timestamp instanceof Date) return timestamp.getTime();
  if (!timestamp) return 0;

  const parsedTimestamp = Date.parse(timestamp);
  return Number.isNaN(parsedTimestamp) ? 0 : parsedTimestamp;
};

const compareMessagesByTimestamp = (left, right) =>
  getMessageTimestamp(left) - getMessageTimestamp(right);

const ensureChronologicalOrder = (messages) => {
  for (let index = 1; index < messages.length; index += 1) {
    if (compareMessagesByTimestamp(messages[index - 1], messages[index]) > 0) {
      return [...messages].sort(compareMessagesByTimestamp);
    }
  }

  return messages;
};

export const collectUniqueMessages = (incomingMessages, processedMessageIds) => {
  if (!Array.isArray(incomingMessages)) {
    throw new Error('Invalid messages format');
  }

  let nextProcessedMessageIds = processedMessageIds;
  const messages = [];

  for (const message of incomingMessages) {
    if (!message?._id || nextProcessedMessageIds.has(message._id)) {
      continue;
    }

    if (nextProcessedMessageIds === processedMessageIds) {
      nextProcessedMessageIds = new Set(processedMessageIds);
    }
    nextProcessedMessageIds.add(message._id);
    messages.push(message);
  }

  return { messages, processedMessageIds: nextProcessedMessageIds };
};

export const mergeSortedMessages = (currentMessages, incomingMessages) => {
  if (incomingMessages.length === 0) {
    return currentMessages;
  }

  const sortedIncomingMessages = ensureChronologicalOrder(incomingMessages);
  if (currentMessages.length === 0) {
    return sortedIncomingMessages;
  }

  const currentFirst = currentMessages[0];
  const currentLast = currentMessages[currentMessages.length - 1];
  const incomingFirst = sortedIncomingMessages[0];
  const incomingLast = sortedIncomingMessages[sortedIncomingMessages.length - 1];

  // 히스토리 페이지는 현재 목록보다 과거이므로 전체 정렬 없이 앞에 붙인다.
  if (compareMessagesByTimestamp(incomingLast, currentFirst) <= 0) {
    return [...sortedIncomingMessages, ...currentMessages];
  }

  // 실시간 메시지처럼 현재 목록 뒤에 오는 경우에도 전체 정렬을 생략한다.
  if (compareMessagesByTimestamp(currentLast, incomingFirst) <= 0) {
    return [...currentMessages, ...sortedIncomingMessages];
  }

  // 시간 범위가 겹치는 예외적인 페이지는 정렬된 두 목록을 선형 병합한다.
  const mergedMessages = [];
  let currentIndex = 0;
  let incomingIndex = 0;

  while (
    currentIndex < currentMessages.length &&
    incomingIndex < sortedIncomingMessages.length
  ) {
    if (
      compareMessagesByTimestamp(
        currentMessages[currentIndex],
        sortedIncomingMessages[incomingIndex]
      ) <= 0
    ) {
      mergedMessages.push(currentMessages[currentIndex]);
      currentIndex += 1;
    } else {
      mergedMessages.push(sortedIncomingMessages[incomingIndex]);
      incomingIndex += 1;
    }
  }

  return [
    ...mergedMessages,
    ...currentMessages.slice(currentIndex),
    ...sortedIncomingMessages.slice(incomingIndex),
  ];
};

export const deriveUniqueSortedMessages = (
  currentMessages,
  incomingMessages,
  processedMessageIds
) => {
  const {
    messages: uniqueIncomingMessages,
    processedMessageIds: nextProcessedMessageIds,
  } = collectUniqueMessages(incomingMessages, processedMessageIds);

  return {
    messages: mergeSortedMessages(
      ensureChronologicalOrder(currentMessages),
      uniqueIncomingMessages
    ),
    processedMessageIds: nextProcessedMessageIds,
  };
};

export const mergeUniqueSortedMessages = (
  currentMessages,
  incomingMessages,
  processedMessageIds
) => {
  return deriveUniqueSortedMessages(
    currentMessages,
    incomingMessages,
    processedMessageIds
  ).messages;
};
