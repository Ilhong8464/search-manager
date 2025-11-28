package com.cp.oslo.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 인덱스 상태 정보 (DB 저장용)
 * 마지막 동기화 시간 등 상태값만 저장합니다.
 * 설정 정보(enabled, interval 등)는 YML 및 코드로 관리됩니다.
 */
@Entity
@Table(name = "index_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IndexState {

    @Id
    @Column(name = "index_name", length = 50)
    private String indexName;

    /**
     * 마지막 동기화 시간
     */
    @Column(name = "last_sync_time")
    private LocalDateTime lastSyncTime;

    /**
     * 마지막 동기화 결과 메시지 (간단 요약)
     */
    @Column(name = "last_sync_status")
    private String lastSyncStatus;
}
