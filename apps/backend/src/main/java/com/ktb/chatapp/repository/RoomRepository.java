package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Room;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends MongoRepository<Room, String> {

    List<Room> findAllByOrderByCreatedAtDesc();

    // 가장 최근에 생성된 방 조회 (Health Check용)
    @Query(value = "{}", sort = "{ 'createdAt': -1 }")
    Optional<Room> findMostRecentRoom();

    @Query("{'_id': ?0}")
    @Update("{'$addToSet': {'participantIds': ?1}}")
    void addParticipant(String roomId, String userId);

    @Query("{'_id': ?0}")
    @Update("{'$pull': {'participantIds': ?1}}")
    void removeParticipant(String roomId, String userId);
}
