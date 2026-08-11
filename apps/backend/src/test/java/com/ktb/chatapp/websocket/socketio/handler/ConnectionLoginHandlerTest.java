package com.ktb.chatapp.websocket.socketio.handler;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionLoginHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private ConnectedUsers connectedUsers;
    @Mock private UserRooms userRooms;
    @Mock private RoomJoinHandler roomJoinHandler;
    @Mock private RoomLeaveHandler roomLeaveHandler;
    @Mock private ScheduledExecutorService duplicateLoginScheduler;
    @Mock private SocketIOClient client;

    private ConnectionLoginHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ConnectionLoginHandler(
                socketIOServer,
                connectedUsers,
                userRooms,
                roomJoinHandler,
                roomLeaveHandler,
                duplicateLoginScheduler,
                new SimpleMeterRegistry());
    }

    @Test
    void onConnect_setsUserRejoinsRoomsStoresUserAndJoinsUserRooms() {
        SocketUser user = new SocketUser("user-1", "tester", "session-1", "socket-1");
        when(connectedUsers.get(user.id())).thenReturn(null);
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1", "room-2"));

        handler.onConnect(client, user);

        verify(client).set("user", user);
        verify(roomJoinHandler).handleJoinRoom(client, "room-1");
        verify(roomJoinHandler).handleJoinRoom(client, "room-2");
        verify(connectedUsers).set(user.id(), user);
        verify(client).joinRooms(Set.of("user:" + user.id(), "room-list"));
    }

    @Test
    void onConnect_duplicateLogin_schedulesDelayedSessionEndOnSharedScheduler() {
        UUID existingSocketId = UUID.randomUUID();
        SocketUser existingUser =
                new SocketUser("user-1", "tester", "session-old", existingSocketId.toString());
        SocketIOClient existingClient = mock(SocketIOClient.class);
        when(connectedUsers.get("user-1")).thenReturn(existingUser);
        when(socketIOServer.getClient(existingSocketId)).thenReturn(existingClient);

        // 새 접속 클라이언트의 handshake 정보(DUPLICATE_LOGIN payload용)
        HandshakeData handshake = mock(HandshakeData.class);
        HttpHeaders headers = mock(HttpHeaders.class);
        when(client.getHandshakeData()).thenReturn(handshake);
        when(handshake.getHttpHeaders()).thenReturn(headers);
        when(headers.get("User-Agent")).thenReturn("JUnit-UA");
        when(client.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
        when(userRooms.get("user-1")).thenReturn(Set.of());

        // 공유 스케줄러가 예약 작업을 즉시 실행하도록 스텁(지연 대기 없이 결정적으로 검증)
        when(duplicateLoginScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                });

        SocketUser newUser = new SocketUser("user-1", "tester", "session-new", "socket-new");
        handler.onConnect(client, newUser);

        // 기존 클라이언트에 즉시 중복 로그인 통지 + (스케줄러 경유) 유예 종료 통지
        verify(existingClient).sendEvent(eq(DUPLICATE_LOGIN), any());
        verify(duplicateLoginScheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        verify(existingClient).sendEvent(eq(SESSION_ENDED), any());
    }

    @Test
    void onDisconnect_removesCurrentConnectionAndLeavesRooms() {
        UUID socketId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", socketId.toString());
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1"));
        when(client.getSessionId()).thenReturn(socketId);
        when(connectedUsers.get(user.id())).thenReturn(user);

        handler.onDisconnect(client);

        verify(roomLeaveHandler).handleLeaveRoom(client, "room-1");
        verify(connectedUsers).del(user.id());
        verify(client).leaveRooms(Set.of("user:" + user.id(), "room-list"));
        verify(client).del("user");
        verify(client).disconnect();
    }
}
