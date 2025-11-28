package com.cp.oslo.controller;

import com.cp.oslo.service.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * uvw_manual_qna 뷰 인덱싱을 위한 전용 REST API 컨트롤러
 * 매뉴얼 Q&A 검색 인덱스를 관리합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/manual-qna")
@RequiredArgsConstructor
public class ManualQnaIndexController {

    private final IndexingService indexingService;

    /**
     * uvw_manual_qna 인덱스를 생성하고 데이터를 동기화합니다.
     *
     * POST /api/v1/manual-qna/create-index
     */
    @PostMapping("/create-index")
    public ResponseEntity<Map<String, Object>> createManualQnaIndex() {
        return syncManualQnaIndex();
    }

    /**
     * 기존 manual_qna 인덱스를 재동기화합니다.
     *
     * POST /api/v1/manual-qna/sync
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncManualQnaIndex() {
        log.info("uvw_manual_qna 인덱스 동기화 요청");
        Map<String, Object> result = new HashMap<>();

        try {
            indexingService.syncIndex("manual-qna");
            result.put("success", true);
            result.put("message", "동기화 작업이 시작되었습니다.");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("동기화 중 오류 발생", e);
            result.put("success", false);
            result.put("message", "동기화 실패: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }
}
