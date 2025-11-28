package com.cp.oslo.controller;

import com.cp.oslo.service.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 데이터 동기화 API 컨트롤러
 * 실시간 인덱싱 등 데이터 동기화 관련 기능을 제공합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
public class DataSyncController {

    private final IndexingService indexingService;

    /**
     * 특정 인덱스의 단건 문서를 실시간으로 동기화(Upsert)합니다.
     * DB에서 해당 UUID의 최신 데이터를 조회하여 인덱싱합니다.
     *
     * POST /api/v1/sync/{indexName}/document
     * Body: { "uuid": "..." }
     */
    @PostMapping("/{indexName}/document")
    public ResponseEntity<Map<String, Object>> syncDocument(
            @PathVariable String indexName,
            @RequestBody Map<String, String> requestBody) {
        
        String uuid = requestBody.get("uuid");
        log.info("단건 동기화 요청: index={}, uuid={}", indexName, uuid);

        Map<String, Object> result = new HashMap<>();

        if (uuid == null || uuid.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "uuid 필드는 필수입니다.");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            indexingService.syncDocument(indexName, uuid);

            result.put("success", true);
            result.put("message", "단건 동기화(인덱싱)가 완료되었습니다.");
            result.put("indexName", indexName);
            result.put("uuid", uuid);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("단건 동기화 중 오류 발생", e);
            result.put("success", false);
            result.put("message", "단건 동기화 실패: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }
}
