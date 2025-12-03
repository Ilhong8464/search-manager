package com.cp.oslo.controller;

import com.cp.oslo.config.IndexRegistry;
import com.cp.oslo.domain.IndexState;
import com.cp.oslo.repository.IndexStateRepository;
import com.cp.oslo.service.IndexingService;
import com.cp.oslo.service.OpenSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * UVW_SEARCH 뷰 인덱싱을 위한 통합 검색(Unified Search) 관리 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/unified-index")
@RequiredArgsConstructor
public class UnifiedIndexController {

    private final IndexingService indexingService;
    private final IndexRegistry indexRegistry;
    private final OpenSearchService openSearchService;
    private final IndexStateRepository indexStateRepository;

    /**
     * unified 인덱스 정보를 조회합니다.
     *
     * GET /api/v1/unified-index/info
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getUnifiedIndexInfo() {
        String indexName = "unified";
        var definition = indexRegistry.get(indexName);

        if (definition == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> info = new HashMap<>();
        boolean exists = openSearchService.indexExists(indexName);
        IndexState state = indexStateRepository.findById(indexName).orElse(null);

        info.put("indexName", indexName);
        info.put("sourceTable", definition.getSourceTableName());
        info.put("description", definition.getDescription());
        info.put("exists", exists);
        info.put("enabled", true);
        info.put("fieldMappingsCount", definition.getFields().size());

        if (state != null) {
            info.put("lastSyncTime", state.getLastSyncTime());
            info.put("lastSyncStatus", state.getLastSyncStatus());
        }

        info.put("syncUrl", "/api/v1/unified-index/sync");
        info.put("searchUrl", "/api/v1/search/unified?query=키워드");

        return ResponseEntity.ok(info);
    }

    /**
     * unified 인덱스를 생성하고 데이터를 동기화합니다.
     *
     * POST /api/v1/unified-index/create-index
     */
    @PostMapping("/create-index")
    public ResponseEntity<Map<String, Object>> createUnifiedIndex() {
        return syncUnifiedIndex();
    }

    /**
     * 기존 unified 인덱스를 재동기화합니다.
     *
     * POST /api/v1/unified-index/sync
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncUnifiedIndex() {
        log.info("unified 인덱스 동기화 요청");
        Map<String, Object> result = new HashMap<>();

        try {
            indexingService.syncIndex("unified");
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
