package com.cp.oslo.scheduler;

import com.cp.oslo.service.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기적 동기화 스케줄러
 * 각 인덱스별로 설정된 주기(Interval)에 따라 동기화를 실행합니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntervalSyncScheduler {

    private final IndexingService indexingService;

    /**
     * 1분마다 실행되어 각 인덱스의 동기화 주기를 체크합니다.
     */
    @Scheduled(fixedDelay = 60000) // 60초(1분)마다 실행
    public void runIntervalSyncCheck() {
        try {
            // 너무 잦은 로그를 방지하기 위해 디버그 레벨이나, 실행된 경우에만 로그를 남기는 로직이 서비스 내부에 있음
            indexingService.checkAndSyncIntervalIndexes();
        } catch (Exception e) {
            log.error("주기적 동기화 체크 중 오류 발생", e);
        }
    }
}
