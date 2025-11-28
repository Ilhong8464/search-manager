package com.cp.oslo.dto;

import com.cp.oslo.domain.SyncHistory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 동기화 이력 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncHistoryResponse {

    private Long id;
    private String indexName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private SyncHistory.SyncStatus status;
    private Long recordsProcessed;
    private Long recordsSucceeded;
    private Long recordsFailed;
    private String errorMessage;
    private Long durationMs;

    public static SyncHistoryResponse from(SyncHistory history) {
        return SyncHistoryResponse.builder()
                .id(history.getId())
                .indexName(history.getIndexName())
                .startTime(history.getStartTime())
                .endTime(history.getEndTime())
                .status(history.getStatus())
                .recordsProcessed(history.getRecordsProcessed())
                .recordsSucceeded(history.getRecordsSucceeded())
                .recordsFailed(history.getRecordsFailed())
                .errorMessage(history.getErrorMessage())
                .durationMs(history.getDurationMs())
                .build();
    }
}
