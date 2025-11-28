package com.cp.oslo.controller;

import com.cp.oslo.service.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * uvw_call 뷰 인덱싱을 위한 전용 REST API 컨트롤러
 * 상담콜 검색 인덱스를 관리합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/call")
@RequiredArgsConstructor
public class CallIndexController {

    private final IndexingService indexingService;

    /**
     * uvw_call 인덱스를 생성하고 데이터를 동기화합니다.
     * 리팩토링 후: 인덱스 설정은 코드/YML로 관리되므로, 단순히 동기화(생성 포함)를 트리거합니다.
     *
     * POST /api/v1/call/create-index
     */
    @PostMapping("/create-index")
    public ResponseEntity<Map<String, Object>> createCallIndex() {
        return syncCallIndex();
    }

    /**
     * 기존 call 인덱스를 재동기화합니다.
     *
     * POST /api/v1/call/sync
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncCallIndex() {
        log.info("uvw_call 인덱스 동기화 요청");
        Map<String, Object> result = new HashMap<>();

        try {
            indexingService.syncIndex("call");
            result.put("success", true);
            result.put("message", "동기화 작업이 시작되었습니다. (Logs 확인)");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("동기화 중 오류 발생", e);
            result.put("success", false);
            result.put("message", "동기화 실패: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }
    
    // info 메서드는 복잡성을 피하기 위해 제거하거나, 상태 조회 서비스가 있다면 연결할 수 있습니다.
    // 현재는 제거합니다.
}
