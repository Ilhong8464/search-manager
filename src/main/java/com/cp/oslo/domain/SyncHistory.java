package com.cp.oslo.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 동기화 이력 엔티티
 */
@Entity
@Table(name = "sync_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 인덱스 이름
     */
    @Column(nullable = false)
    private String indexName;

    /**
     * 동기화 시작 시간
     */
    @Column(nullable = false)
    private LocalDateTime startTime;

    /**
     * 동기화 종료 시간
     */
    private LocalDateTime endTime;

    /**
     * 동기화 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncStatus status;

    /**
     * 처리된 레코드 수
     */
    @Builder.Default
    private Long recordsProcessed = 0L;

    /**
     * 성공한 레코드 수
     */
    @Builder.Default
    private Long recordsSucceeded = 0L;

    /**
     * 실패한 레코드 수
     */
    @Builder.Default
    private Long recordsFailed = 0L;

    /**
     * 에러 메시지
     */
    @Column(length = 2000)
    private String errorMessage;

    /**
     * 소요 시간 (밀리초)
     */
    private Long durationMs;

    /**
     * 동기화 상태 열거형
     */
    public enum SyncStatus {
        RUNNING,    // 실행 중
        SUCCESS,    // 성공
        FAILED,     // 실패
        PARTIAL     // 부분 성공
    }

    /**
     * 동기화 완료 처리
     */
    public void complete(SyncStatus status, String errorMessage) {
        this.endTime = LocalDateTime.now();
        this.status = status;
        this.errorMessage = errorMessage;
        if (startTime != null && endTime != null) {
            this.durationMs = java.time.Duration.between(startTime, endTime).toMillis();
        }
    }
}
