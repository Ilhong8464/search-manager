package com.cp.oslo.controller;

import com.cp.oslo.service.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * uvw_doc_notice 뷰 인덱싱을 위한 전용 REST API 컨트롤러
 * 문서/공지 검색 인덱스를 관리합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/doc-notice")
@RequiredArgsConstructor
public class DocNoticeIndexController {

    private final IndexingService indexingService;

    /**
     * uvw_doc_notice 인덱스를 생성하고 데이터를 동기화합니다.
     *
     * POST /api/v1/doc-notice/create-index
     */
    @PostMapping("/create-index")
    public ResponseEntity<Map<String, Object>> createDocNoticeIndex() {
        return syncDocNoticeIndex();
    }

    /**
     * 기존 doc-notice 인덱스를 재동기화합니다.
     *
     * POST /api/v1/doc-notice/sync
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncDocNoticeIndex() {
        log.info("uvw_doc_notice 인덱스 동기화 요청");
        Map<String, Object> result = new HashMap<>();

        try {
            indexingService.syncIndex("doc-notice");
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
