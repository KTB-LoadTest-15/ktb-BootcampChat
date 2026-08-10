package com.ktb.chatapp.websocket.socketio;

/**
 * Socket.IO 이벤트의 블로킹 처리를 Netty 이벤트 루프에서 분리해 실행하는 디스패처.
 *
 * <p>netty-socketio는 {@code @OnEvent} 핸들러를 Netty worker(event-loop) 스레드에서 동기로 호출한다.
 * 핸들러가 DB I/O 등으로 블로킹하면 같은 스레드가 처리하는 다른 연결의 프레임 read/write와 heartbeat까지
 * 지연된다. 이 디스패처는 처리 본문을 전용 워커로 넘겨 event-loop을 프레임 I/O 전용으로 유지한다.
 *
 * <p><b>순서 보장</b>: {@code orderingKey}가 같은 작업은 제출 순서(FIFO)대로 실행된다(예: 같은 방의 메시지).
 * 서로 다른 key는 병렬 처리될 수 있다.
 */
public interface SocketDispatcher {

    /**
     * {@code task}를 워커에서 실행한다. 같은 {@code orderingKey}의 작업끼리는 FIFO 순서가 보장된다.
     * 워커가 포화(큐 가득)면 {@code task}를 실행하지 않고 {@code onReject}를 호출한다(드롭 + 백프레셔).
     *
     * @param orderingKey 순서 보장 단위(예: roomId). 같은 key → 같은 레인 → FIFO.
     * @param task        event-loop에서 분리해 실행할 처리 본문
     * @param onReject    포화로 실행하지 못할 때의 처리(예: 클라이언트에 혼잡 에러 통지)
     */
    void dispatch(String orderingKey, Runnable task, Runnable onReject);
}
