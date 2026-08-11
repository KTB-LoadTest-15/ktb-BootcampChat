package com.ktb.chatapp.perf;

import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * MongoDB 드라이버 레벨에서 실제로 나가는 wire 명령을 카운트하는 측정용 리스너.
 *
 * <p>성능 개선의 "쿼리 몇 번 나가는가"를 추정이 아니라 실측하기 위한 도구다.
 * {@link #start()}로 기록을 켜고 측정 대상 연산을 수행한 뒤 {@link #snapshot()}으로 집계한다.
 * heartbeat(hello/ping) 등 관리 명령은 {@link #totalDataCommands()}에서 제외한다.
 */
public class CommandCountingListener implements CommandListener {

    private static final java.util.Set<String> DATA_COMMANDS = java.util.Set.of(
            "find", "insert", "update", "delete", "aggregate", "getMore", "count", "findAndModify");

    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();
    private volatile boolean recording = false;

    @Override
    public void commandStarted(CommandStartedEvent event) {
        if (!recording) {
            return;
        }
        counts.computeIfAbsent(event.getCommandName(), k -> new LongAdder()).increment();
    }

    /** 기록을 초기화하고 켠다. */
    public void start() {
        counts.clear();
        recording = true;
    }

    /** 기록을 끈다. */
    public void stop() {
        recording = false;
    }

    public long count(String commandName) {
        LongAdder a = counts.get(commandName);
        return a == null ? 0 : a.sum();
    }

    /** heartbeat 등을 제외한 데이터 명령의 총 횟수. */
    public long totalDataCommands() {
        return DATA_COMMANDS.stream().mapToLong(this::count).sum();
    }

    /** 명령 이름별 횟수 스냅샷 (데이터 명령만, 정렬된 맵). */
    public Map<String, Long> snapshot() {
        Map<String, Long> m = new TreeMap<>();
        counts.forEach((k, v) -> {
            if (DATA_COMMANDS.contains(k)) {
                m.put(k, v.sum());
            }
        });
        return m;
    }
}
