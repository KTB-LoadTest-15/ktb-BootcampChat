package com.ktb.chatapp.websocket.socketio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.MessagesReadResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGES_READ;

class ReadReceiptCoalescerTest {

    private final SocketIOServer server = mock(SocketIOServer.class);
    private final BroadcastOperations roomOps = mock(BroadcastOperations.class);

    @Test
    void enqueue_withZeroWindow_broadcastsImmediately() {
        when(server.getRoomOperations("room-1")).thenReturn(roomOps);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ReadReceiptCoalescer coalescer = new ReadReceiptCoalescer(server, scheduler, 0);

        coalescer.enqueue("room-1", "user-1", 1000L);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(roomOps).sendEvent(eq(MESSAGES_READ), captor.capture());
        assertThat(((MessagesReadResponse) captor.getValue()).getCursors())
                .containsExactly(java.util.Map.entry("user-1", 1000L));
        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    void enqueue_coalescesRoomUpdatesIntoSingleBroadcastWithMaxTs() {
        when(server.getRoomOperations("room-1")).thenReturn(roomOps);

        // 스케줄러: 예약된 flush Runnable을 잡아 두었다가 창 만료를 수동으로 시뮬레이션한다.
        List<Runnable> scheduled = new ArrayList<>();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        doAnswer(inv -> {
            scheduled.add(inv.getArgument(0));
            return null;
        }).when(scheduler).schedule(any(Runnable.class), anyLong(), any());

        ReadReceiptCoalescer coalescer = new ReadReceiptCoalescer(server, scheduler, 100);

        coalescer.enqueue("room-1", "user-1", 1000L);
        coalescer.enqueue("room-1", "user-2", 2000L);
        coalescer.enqueue("room-1", "user-1", 1500L); // 같은 유저 최댓값 갱신

        // 창 만료 전엔 broadcast 없음, flush는 한 번만 예약됨.
        verify(roomOps, never()).sendEvent(eq(MESSAGES_READ), any());
        assertThat(scheduled).hasSize(1);

        // 창 만료 시뮬레이션.
        scheduled.get(0).run();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(roomOps, times(1)).sendEvent(eq(MESSAGES_READ), captor.capture());
        assertThat(((MessagesReadResponse) captor.getValue()).getCursors())
                .containsOnly(
                        java.util.Map.entry("user-1", 1500L),
                        java.util.Map.entry("user-2", 2000L));
    }

    @Test
    void flush_afterWindow_reschedulesForNextGeneration() {
        when(server.getRoomOperations("room-1")).thenReturn(roomOps);
        List<Runnable> scheduled = new ArrayList<>();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        doAnswer(inv -> {
            scheduled.add(inv.getArgument(0));
            return null;
        }).when(scheduler).schedule(any(Runnable.class), anyLong(), any());

        ReadReceiptCoalescer coalescer = new ReadReceiptCoalescer(server, scheduler, 100);

        coalescer.enqueue("room-1", "user-1", 1000L);
        scheduled.get(0).run(); // 1세대 flush

        coalescer.enqueue("room-1", "user-1", 3000L); // 2세대 → 새 창 예약
        assertThat(scheduled).hasSize(2);
        scheduled.get(1).run();

        verify(roomOps, times(2)).sendEvent(eq(MESSAGES_READ), any());
    }
}
