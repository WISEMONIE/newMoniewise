package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.UserEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface UserEventRepository extends JpaRepository<UserEvent, Long> {

    @Query(value = "SELECT event_name, COUNT(*) AS cnt FROM user_events " +
            "WHERE created_at >= :since GROUP BY event_name ORDER BY cnt DESC LIMIT :limit",
            nativeQuery = true)
    List<Object[]> countByEventNameSince(@Param("since") LocalDateTime since, @Param("limit") int limit);

    @Query(value = "SELECT screen_name, COUNT(*) AS cnt FROM user_events " +
            "WHERE event_name = 'screen_view' AND created_at >= :since AND screen_name IS NOT NULL " +
            "GROUP BY screen_name ORDER BY cnt DESC LIMIT :limit",
            nativeQuery = true)
    List<Object[]> topScreensSince(@Param("since") LocalDateTime since, @Param("limit") int limit);

    @Query(value = "SELECT COUNT(DISTINCT user_id) FROM user_events WHERE created_at >= :since",
            nativeQuery = true)
    long countDistinctUsersSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT DATE(created_at) AS day, COUNT(DISTINCT user_id) AS dau " +
            "FROM user_events WHERE created_at >= :since " +
            "GROUP BY DATE(created_at) ORDER BY day DESC LIMIT :limit",
            nativeQuery = true)
    List<Object[]> dailyActiveUsersSince(@Param("since") LocalDateTime since, @Param("limit") int limit);
}
