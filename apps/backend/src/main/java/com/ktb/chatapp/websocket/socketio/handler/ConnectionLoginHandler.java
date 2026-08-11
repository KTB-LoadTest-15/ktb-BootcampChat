package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * Socket.IO Chat Handler
 * 어노테이션 기반 이벤트 처리와 인증 흐름을 정의한다.
 * 연결/해제 및 중복 로그인 처리를 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class ConnectionLoginHandler {

    /** 중복 로그인 감지 후 기존 세션을 실제 종료하기까지의 유예(초). */
    private static final long DUPLICATE_LOGIN_GRACE_SECONDS = 10;

    private final SocketIOServer socketIOServer;
    private final ConnectedUsers connectedUsers;
    private final UserRooms userRooms;
    private final RoomJoinHandler roomJoinHandler;
    private final RoomLeaveHandler roomLeaveHandler;
    /** 중복 로그인 유예 종료를 예약하는 공유 스케줄러(접속마다 스레드를 새로 만들지 않는다). */
    private final ScheduledExecutorService duplicateLoginScheduler;

    public ConnectionLoginHandler(
            SocketIOServer socketIOServer,
            ConnectedUsers connectedUsers,
            UserRooms userRooms,
            RoomJoinHandler roomJoinHandler,
            RoomLeaveHandler roomLeaveHandler,
            ScheduledExecutorService duplicateLoginScheduler,
            MeterRegistry meterRegistry) {
        this.socketIOServer = socketIOServer;
        this.connectedUsers = connectedUsers;
        this.userRooms = userRooms;
        this.roomJoinHandler = roomJoinHandler;
        this.roomLeaveHandler = roomLeaveHandler;
        this.duplicateLoginScheduler = duplicateLoginScheduler;

        // Register gauge metric for concurrent users
        Gauge.builder("socketio.concurrent.users", connectedUsers::size)
                .description("Current number of concurrent Socket.IO users")
                .register(meterRegistry);
    }
    
    /**
     * auth 처리가 선행되어야 해서 @OnConnect 대신 별도 메서드로 구현
     */
    public void onConnect(SocketIOClient client, SocketUser user) {
        String userId = user.id();
        
        try {
            // 다른 노드에 접속된 사용자는 통보 불가
            notifyDuplicateLogin(client, userId);
            client.set("user", user);
            
            userRooms.get(userId).forEach(roomId -> {
                // 재접속 시 기존 참여 방 재입장 처리
                roomJoinHandler.handleJoinRoom(client, roomId);
            });
            
            connectedUsers.set(userId, user);

            log.info("Socket.IO user connected: {} ({}) - Total concurrent users: {}",
                    getUserName(client), userId, connectedUsers.size());

            // socket:{socketId} 룸에도 조인 → 다른 노드에서 이 특정 소켓만 타깃해 전송할 수 있다
            // (중복 로그인 시 기존 소켓 종료 통보를 RedissonStoreFactory로 크로스노드 전달).
            client.joinRooms(Set.of("user:" + userId, "room-list", "socket:" + client.getSessionId()));
            
        } catch (Exception e) {
            log.error("Error handling Socket.IO connection", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "연결 처리 중 오류가 발생했습니다."
            ));
        }
    }
    
    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {
        String userId = getUserId(client);
        String userName = getUserName(client);
        
        try {
            if (userId == null) {
                return;
            }
            
            userRooms.get(userId).forEach(roomId -> {
                roomLeaveHandler.handleLeaveRoom(client, roomId);
            });
            String socketId = client.getSessionId().toString();
            
            // 해당 사용자의 현재 활성 연결인 경우에만 정리
            var socketUser = connectedUsers.get(userId);
            if (socketUser != null && socketId.equals(socketUser.socketId())) {
                connectedUsers.del(userId);
            } else {
                log.warn("Socket.IO disconnect: User {} has a different active connection. Skipping cleanup.", userId);
            }

            client.leaveRooms(Set.of("user:" + userId, "room-list", "socket:" + socketId));
            client.del("user");
            client.disconnect();

            log.info("Socket.IO user disconnected: {} ({}) - Total concurrent users: {}",
                    userName, userId, connectedUsers.size());
        } catch (Exception e) {
            log.error("Error handling Socket.IO disconnection", e);
            client.sendEvent(ERROR, Map.of(
                "message", "연결 종료 처리 중 오류가 발생했습니다."
            ));
        }
        
    }
    
    private SocketUser getUserDto(SocketIOClient client) {
        return client.get("user");
    }
    
    private String getUserId(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.id() : null;
    }
    
    private String getUserName(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.name() : null;
    }
    
    /**
     * 중복 로그인 시 기존 소켓에 종료를 통보한다.
     *
     * <p>기존 소켓이 다른 노드에 있을 수 있으므로 {@code getClient(socketId)}(로컬 레지스트리 전용)
     * 대신 {@code socket:{socketId}} 룸으로 전송한다. {@code socketio.store=redisson}이면
     * RedissonStoreFactory가 이 룸 브로드캐스트를 해당 소켓이 있는 노드까지 fan-out 한다(②).
     * 새 클라이언트는 자신의 {@code socket:{자기id}}에만 조인하므로, 지연 SESSION_ENDED가 새(정상)
     * 세션을 오폭하지 않는다.
     *
     * <p>기존 세션을 노드 간에 찾으려면 {@code ConnectedUsers}가 공유돼야 한다(③,
     * {@code socketio.store=redisson} 시 RedisChatDataStore). memory 모드에서는 단일노드 내에서만
     * 동작하며 기존과 동치다.
     */
    private void notifyDuplicateLogin(SocketIOClient client, String userId) {
        var socketUser = connectedUsers.get(userId);
        if (socketUser == null) {
            return;
        }
        String existingSocketId = socketUser.socketId();
        String targetRoom = "socket:" + existingSocketId;

        // payload 값에 null이 섞이면 Map.of가 NPE를 던지므로 기본값으로 보정한다.
        // (웹소켓 전송 클라이언트는 User-Agent 헤더가 없을 수 있다.)
        String userAgent = client.getHandshakeData().getHttpHeaders().get("User-Agent");
        var remoteAddress = client.getRemoteAddress();

        // Send duplicate login notification (해당 소켓이 있는 노드로 전달됨)
        socketIOServer.getRoomOperations(targetRoom).sendEvent(DUPLICATE_LOGIN, Map.of(
                "type", "new_login_attempt",
                "deviceInfo", userAgent != null ? userAgent : "unknown",
                "ipAddress", remoteAddress != null ? remoteAddress.toString() : "unknown",
                "timestamp", System.currentTimeMillis()
        ));

        // 접속마다 raw Thread를 만들지 않고 공유 스케줄러에 유예 종료를 예약한다(스레드 폭발 방지).
        duplicateLoginScheduler.schedule(() -> {
            try {
                socketIOServer.getRoomOperations(targetRoom).sendEvent(SESSION_ENDED, Map.of(
                        "reason", "duplicate_login",
                        "message", "다른 기기에서 로그인하여 현재 세션이 종료되었습니다."
                ));
            } catch (Exception e) {
                log.error("Error sending delayed session_ended for duplicate login", e);
            }
        }, DUPLICATE_LOGIN_GRACE_SECONDS, TimeUnit.SECONDS);
    }
}
