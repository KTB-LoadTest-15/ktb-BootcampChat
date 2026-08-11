package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.codec.http.HttpHeaders;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.DUPLICATE_LOGIN;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.SESSION_ENDED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionLoginHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private ConnectedUsers connectedUsers;
    @Mock private UserRooms userRooms;
    @Mock private RoomLeaveHandler roomLeaveHandler;
    @Mock private ScheduledExecutorService duplicateLoginScheduler;
    @Mock private ScheduledExecutorService disconnectGraceScheduler;
    @Mock private SocketIOClient client;

    private ConnectionLoginHandler handler(long graceMs) {
        return new ConnectionLoginHandler(
                socketIOServer,
                connectedUsers,
                userRooms,
                roomLeaveHandler,
                duplicateLoginScheduler,
                disconnectGraceScheduler,
                graceMs,
                new SimpleMeterRegistry());
    }

    private ConnectionLoginHandler handler;

    @BeforeEach
    void setUp() {
        handler = handler(10_000);
    }

    @Test
    void onConnect_rejoinsRoomsForDelivery_storesUser_joinsSystemRooms() {
        UUID sid = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", "socket-1");
        when(connectedUsers.get(user.id())).thenReturn(null); // no duplicate
        when(client.get("user")).thenReturn(user);
        when(client.getSessionId()).thenReturn(sid);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1", "room-2"));

        handler.onConnect(client, user);

        verify(client).set("user", user);
        // 재접속: 멤버 방에 소켓만 재구독(딜리버리용) — handleJoinRoom(브로드캐스트) 아님
        verify(client).joinRoom("room-1");
        verify(client).joinRoom("room-2");
        verify(connectedUsers).set(user.id(), user);
        verify(client).joinRooms(Set.of("user:" + user.id(), "room-list", "socket:" + sid));
    }

    @Test
    void onConnect_duplicateLogin_schedulesDelayedSessionEndOnSharedScheduler() {
        UUID existingSocketId = UUID.randomUUID();
        SocketUser existingUser =
                new SocketUser("user-1", "tester", "session-old", existingSocketId.toString());
        String targetRoom = "socket:" + existingSocketId;
        BroadcastOperations targetOps = mock(BroadcastOperations.class);
        when(connectedUsers.get("user-1")).thenReturn(existingUser);
        when(socketIOServer.getRoomOperations(targetRoom)).thenReturn(targetOps);

        when(client.getSessionId()).thenReturn(UUID.randomUUID());
        HandshakeData handshake = mock(HandshakeData.class);
        HttpHeaders headers = mock(HttpHeaders.class);
        when(client.getHandshakeData()).thenReturn(handshake);
        when(handshake.getHttpHeaders()).thenReturn(headers);
        when(headers.get("User-Agent")).thenReturn("JUnit-UA");
        when(client.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
        when(userRooms.get("user-1")).thenReturn(Set.of());

        when(duplicateLoginScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                });

        SocketUser newUser = new SocketUser("user-1", "tester", "session-new", "socket-new");
        handler.onConnect(client, newUser);

        verify(targetOps).sendEvent(eq(DUPLICATE_LOGIN), any());
        verify(duplicateLoginScheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        verify(targetOps).sendEvent(eq(SESSION_ENDED), any());
    }

    @Test
    void onDisconnect_withGrace_schedulesLeave_notImmediate() {
        UUID socketId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", socketId.toString());
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1"));
        when(client.getSessionId()).thenReturn(socketId);
        when(connectedUsers.get(user.id())).thenReturn(user);
        when(disconnectGraceScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledFuture.class)); // do NOT run — grace pending

        handler.onDisconnect(client);

        // 즉시 퇴장하지 않고 유예 예약
        verify(disconnectGraceScheduler)
                .schedule(any(Runnable.class), eq(10_000L), eq(TimeUnit.MILLISECONDS));
        verify(roomLeaveHandler, never()).leaveRoomByUser(anyString(), anyString(), anyString());
        // 연결 정리는 즉시
        verify(connectedUsers).del(user.id());
        verify(client).leaveRooms(Set.of("user:" + user.id(), "room-list", "socket:" + socketId));
        verify(client).disconnect();
    }

    @Test
    void onDisconnect_noGrace_leavesImmediately() {
        ConnectionLoginHandler noGrace = handler(0);
        UUID socketId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", socketId.toString());
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1"));
        when(client.getSessionId()).thenReturn(socketId);
        when(connectedUsers.get(user.id())).thenReturn(user);

        noGrace.onDisconnect(client);

        verify(roomLeaveHandler).leaveRoomByUser("user-1", "tester", "room-1");
        verify(disconnectGraceScheduler, never())
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onConnect_cancelsPendingLeaveFromRefresh() {
        UUID socketId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", socketId.toString());
        ScheduledFuture<Object> pending = mock(ScheduledFuture.class);
        when(client.get("user")).thenReturn(user);
        when(userRooms.get("user-1")).thenReturn(Set.of("room-1"));
        when(client.getSessionId()).thenReturn(socketId);
        when(connectedUsers.get("user-1")).thenReturn(user).thenReturn(null);
        when(disconnectGraceScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn((ScheduledFuture) pending);

        handler.onDisconnect(client); // 유예 퇴장 예약(pending)
        handler.onConnect(client, user); // 재연결 → 취소돼야 함

        verify(pending).cancel(false);
    }
}
