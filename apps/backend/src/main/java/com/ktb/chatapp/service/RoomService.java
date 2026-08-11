package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.data.domain.PageRequest;
import org.bson.types.ObjectId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final RecentMessageCounter recentMessageCounter;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public RoomsResponse getAllRooms(String name, String encodedCursor, int requestedLimit) {

        try {
            int limit = Math.max(1, Math.min(requestedLimit, 100));
            PageRequest pageRequest = PageRequest.of(0, limit + 1);
            RoomCursor cursor = decodeCursor(encodedCursor);
            List<Room> fetchedRooms = cursor == null
                    ? roomRepository.findAllByOrderByCreatedAtDescIdDesc(pageRequest)
                    : roomRepository.findNextPage(cursor.createdAt(), cursor.id(), pageRequest);
            boolean hasMore = fetchedRooms.size() > limit;
            List<Room> rooms = hasMore
                    ? fetchedRooms.subList(0, limit)
                    : fetchedRooms;
            Map<String, User> usersById = loadUsersById(rooms);
            Map<String, Long> recentMessageCounts = recentMessageCounter.countRecentMessages(
                    rooms.stream().map(Room::getId).toList());

            List<RoomResponse> roomResponses = rooms.stream()
                .map(room -> mapToRoomResponse(room, name, usersById,
                        recentMessageCounts.getOrDefault(room.getId(), 0L).intValue()))
                .collect(Collectors.toList());

            PageMetadata metadata = PageMetadata.builder()
                .total(-1)
                .page(0)
                .pageSize(limit)
                .totalPages(-1)
                .hasMore(hasMore)
                .currentCount(roomResponses.size())
                .nextCursor(hasMore ? encodeCursor(rooms.get(rooms.size() - 1)) : null)
                .sort(PageMetadata.SortInfo.builder()
                    .field("createdAt,_id")
                    .order("desc")
                    .build())
                .build();

            return RoomsResponse.builder()
                .success(true)
                .data(roomResponses)
                .metadata(metadata)
                .build();

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("방 목록 조회 에러", e);
            return RoomsResponse.builder()
                .success(false)
                .data(List.of())
                .build();
        }
    }

    private String encodeCursor(Room room) {
        String value = room.getCreatedAt() + "|" + room.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private RoomCursor decodeCursor(String encodedCursor) {
        if (encodedCursor == null || encodedCursor.isBlank()) return null;
        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(encodedCursor), StandardCharsets.UTF_8);
            int separator = value.lastIndexOf('|');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("잘못된 방 목록 커서입니다.");
            }
            return new RoomCursor(
                    LocalDateTime.parse(value.substring(0, separator)),
                    new ObjectId(value.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("잘못된 방 목록 커서입니다.", e);
        }
    }

    private record RoomCursor(LocalDateTime createdAt, ObjectId id) {}

    public HealthResponse getHealthStatus() {
        long startTime = System.currentTimeMillis();

        try {
            // 최근 방 조회 한 번으로 연결 상태, 지연 시간, 최근 활동을 함께 확인한다.
            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);
            long latency = System.currentTimeMillis() - startTime;

            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                .connected(true)
                .latency(latency)
                .build());

            return HealthResponse.builder()
                .success(true)
                .services(services)
                .lastActivity(lastActivity)
                .build();

        } catch (Exception e) {
            log.warn("MongoDB 연결 확인 실패", e);
            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                .connected(false)
                .latency(System.currentTimeMillis() - startTime)
                .build());

            return HealthResponse.builder()
                .success(false)
                .services(services)
                .build();
        }
    }

    public Room createRoom(CreateRoomRequest createRoomRequest, String name) {
        User creator = userRepository.findByEmail(name)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        Room room = new Room();
        room.setName(createRoomRequest.getName().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (createRoomRequest.getPassword() != null && !createRoomRequest.getPassword().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(createRoomRequest.getPassword()));
        }

        Room savedRoom = roomRepository.save(room);
        
        // Publish event for room created
        try {
            RoomResponse roomResponse = mapToRoomResponse(savedRoom, name);
            eventPublisher.publishEvent(new RoomCreatedEvent(this, roomResponse));
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
        }
        
        return savedRoom;
    }

    public Optional<Room> findRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    public Room joinRoom(String roomId, String password, String name) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return null;
        }

        Room room = roomOpt.get();
        User user = userRepository.findByEmail(name)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        // 비밀번호 확인
        if (room.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        // 이미 참여중인지 확인
        if (!room.getParticipantIds().contains(user.getId())) {
            // 채팅방 참여
            room.getParticipantIds().add(user.getId());
            room = roomRepository.save(room);
        }
        
        // Publish event for room updated
        try {
            RoomResponse roomResponse = mapToRoomResponse(room, name);
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, roomResponse));
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발행 실패", e);
        }

        return room;
    }

    private RoomResponse mapToRoomResponse(Room room, String name) {
        if (room == null) return null;

        return mapToRoomResponse(room, name, loadUsersById(List.of(room)));
    }

    private Map<String, User> loadUsersById(List<Room> rooms) {
        Set<String> userIds = new HashSet<>();
        for (Room room : rooms) {
            if (room.getCreator() != null) {
                userIds.add(room.getCreator());
            }
            if (room.getParticipantIds() != null) {
                userIds.addAll(room.getParticipantIds());
            }
        }

        return userRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(User::getId, user -> user));
    }

    private RoomResponse mapToRoomResponse(Room room, String name, Map<String, User> usersById) {
        return mapToRoomResponse(room, name, usersById,
                recentMessageCounter.countRecentMessages(room.getId()));
    }

    private RoomResponse mapToRoomResponse(
            Room room, String name, Map<String, User> usersById, int recentMessageCount) {
        if (room == null) return null;

        User creator = usersById.get(room.getCreator());

        List<User> participants = Optional.ofNullable(room.getParticipantIds())
            .orElseGet(Set::of)
            .stream()
            .map(usersById::get)
            .filter(java.util.Objects::nonNull)
            .toList();

        return RoomResponse.builder()
            .id(room.getId())
            .name(room.getName() != null ? room.getName() : "제목 없음")
            .hasPassword(room.isHasPassword())
            .creator(creator != null ? UserResponse.builder()
                .id(creator.getId())
                .name(creator.getName() != null ? creator.getName() : "알 수 없음")
                .email(creator.getEmail() != null ? creator.getEmail() : "")
                .build() : null)
            .participants(participants.stream()
                .filter(p -> p != null && p.getId() != null)
                .map(p -> UserResponse.builder()
                    .id(p.getId())
                    .name(p.getName() != null ? p.getName() : "알 수 없음")
                    .email(p.getEmail() != null ? p.getEmail() : "")
                    .build())
                .collect(Collectors.toList()))
            .createdAtDateTime(room.getCreatedAt())
            .isCreator(creator != null
                && name != null
                && name.equalsIgnoreCase(creator.getEmail()))
            .recentMessageCount(recentMessageCount)
            .build();
    }
}
