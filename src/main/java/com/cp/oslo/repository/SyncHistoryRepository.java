package com.cp.oslo.repository;

import com.cp.oslo.domain.SyncHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SyncHistoryRepository extends JpaRepository<SyncHistory, Long> {

    /**
     * 인덱스 이름으로 이력 조회 (최신순)
     */
    List<SyncHistory> findByIndexNameOrderByStartTimeDesc(String indexName);

    /**
     * 특정 기간의 이력 조회
     */
    List<SyncHistory> findByStartTimeBetweenOrderByStartTimeDesc(
            LocalDateTime startTime,
            LocalDateTime endTime
    );

    /**
     * 상태별 이력 조회
     */
    List<SyncHistory> findByStatusOrderByStartTimeDesc(SyncHistory.SyncStatus status);

    /**
     * 인덱스 이름의 최근 10개 이력 조회
     */
    List<SyncHistory> findTop10ByIndexNameOrderByStartTimeDesc(String indexName);
}
