package com.cp.oslo.scheduler;

import com.cp.oslo.service.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 전역 동기화 스케줄러
 * 정해진 시간에 모든 활성화된 인덱스를 순차적으로 동기화합니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GlobalSyncScheduler {

    private final IndexingService indexingService;

    /**
     * 매일 새벽 1시에 전체 동기화 실행
     * application.yml의 search.sync.cron 설정값을 따르며, 기본값은 매일 01:00:00 입니다.
     */
    @Scheduled(cron = "${search.sync.cron:0 0 1 * * *}")
    public void runDailySync() {
        log.info("========================================");
        log.info("스케줄러에 의한 전체 동기화 시작 (Time: {})", LocalDateTime.now());
        log.info("========================================");

        try {
            indexingService.reindexAllEnabledIndexes();
            log.info("스케줄러 전체 동기화(Re-index) 작업 완료");
        } catch (Exception e) {
            log.error("스케줄러 전체 동기화 중 오류 발생", e);
        }
    }
}
