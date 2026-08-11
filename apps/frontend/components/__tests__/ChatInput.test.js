import React from 'react';
import { render, waitFor, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChatInput from '../ChatInput';

describe('ChatInput', () => {
  it('renders the lazy emoji picker under React 19', async () => {
    const { container, getByLabelText } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );

    fireEvent.click(getByLabelText('이모티콘'));

    await waitFor(() => {
      expect(container.querySelector('em-emoji-picker')).toBeInTheDocument();
    });
  });

  it('does not submit while Enter is completing IME composition', () => {
    const onSubmit = vi.fn();
    const { getByTestId } = render(
      <ChatInput
        onSubmit={onSubmit}
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );
    const input = getByTestId('chat-message-input');

    fireEvent.change(input, { target: { value: '안녕하세요' } });
    fireEvent.keyDown(input, {
      key: 'Enter',
      code: 'Enter',
      keyCode: 229,
      isComposing: true,
    });

    expect(onSubmit).not.toHaveBeenCalled();
    expect(input).toHaveValue('안녕하세요');
  });

  it('submits a completed message with Enter', () => {
    const onSubmit = vi.fn();
    const { getByTestId } = render(
      <ChatInput
        onSubmit={onSubmit}
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );
    const input = getByTestId('chat-message-input');

    fireEvent.change(input, { target: { value: '안녕하세요' } });
    fireEvent.keyDown(input, {
      key: 'Enter',
      code: 'Enter',
      keyCode: 13,
    });

    expect(onSubmit).toHaveBeenCalledWith({
      type: 'text',
      content: '안녕하세요',
    });
    expect(input).toHaveValue('');
  });

  it('keeps the input and send button disabled until the room is ready', () => {
    const onSubmit = vi.fn();
    const { getByTestId } = render(
      <ChatInput
        onSubmit={onSubmit}
        disabled={true}
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );

    const input = getByTestId('chat-message-input');
    const sendButton = getByTestId('chat-send-button');

    expect(input).toBeDisabled();
    expect(sendButton).toBeDisabled();
  });
});
