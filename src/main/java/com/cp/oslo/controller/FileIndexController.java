package com.cp.oslo.controller;

import com.cp.oslo.client.EmbeddingClient;
import com.cp.oslo.dto.SearchResultDto;
import com.cp.oslo.service.FileIndexingService;
import com.cp.oslo.service.IndexingService;
import com.cp.oslo.service.OpenSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/file-index")
@RequiredArgsConstructor
@Slf4j
public class FileIndexController {

    private final FileIndexingService fileIndexingService;
    private final IndexingService indexingService;
    private final OpenSearchService openSearchService;
    private final EmbeddingClient embeddingClient;

    private final com.cp.oslo.config.SearchIndexProperties searchIndexProperties;

    @PostMapping("/sync")
    public ResponseEntity<?> sync() {
        // 대량의 파일 처리 시 시간이 오래 걸릴 수 있으므로 비동기 처리를 고려해야 하지만,
        // 현재는 명시적 호출에 의한 동기 처리로 구현합니다.
        indexingService.syncIndex("file");
        return ResponseEntity.ok(Map.of("message", "파일 인덱싱이 완료되었습니다."));
    }

    @DeleteMapping
    public ResponseEntity<?> deleteIndex() {
        boolean deleted = fileIndexingService.deleteIndex();
        return ResponseEntity.ok(Map.of("message", "파일 인덱스 삭제 완료", "deleted", deleted));
    }

    @DeleteMapping("/{uuid}") // UUID를 PathVariable로 받음
    public ResponseEntity<?> deleteFileDocument(@PathVariable String uuid) {
        indexingService.deleteDocument("file", uuid); // IndexingService의 deleteDocument 호출
        return ResponseEntity.ok(Map.of("message", "파일 문서 삭제 완료", "uuid", uuid));
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String query,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Double textWeight) {
        
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        if (size == null) {
            Integer defaultSize = searchIndexProperties.getIndexes().get("file").getDefaultSearchSize();
            size = (defaultSize != null) ? defaultSize : 10;
        }
        
        // Validate
        size = Math.max(1, Math.min(size, 100));

        // 전문 검색 (Full-text Search) 수행
        // textWeight는 사용하지 않음
        SearchResultDto result = openSearchService.searchNestedText("file", "paragraphs", "content", query, size);
        
        return ResponseEntity.ok(processResponse(result));
    }

    @GetMapping("/search/text")
    public ResponseEntity<Map<String, Object>> searchText(
            @RequestParam String query,
            @RequestParam(required = false) Integer size) {
        
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        if (size == null) {
            Integer defaultSize = searchIndexProperties.getIndexes().get("file").getDefaultSearchSize();
            size = (defaultSize != null) ? defaultSize : 10;
        }
        size = Math.max(1, Math.min(size, 100));

        SearchResultDto result = fileIndexingService.searchText(query, size);
        return ResponseEntity.ok(processResponse(result));
    }

    private Map<String, Object> processResponse(SearchResultDto result) {
        Map<String, Map<String, List<String>>> highlights = result.getHighlights(); // 하이라이트 정보 가져오기
        
        List<Map<String, Object>> processedDocuments = result.getDocuments().stream()
                .map(doc -> {
                    Map<String, Object> newDoc = new HashMap<>();
                    String fileUuid = doc.get("FILE_UUID").toString();
                    String docId = doc.get("_id").toString();
                    
                    // 1. FILE_NM 처리 (하이라이트 우선)
                    if (highlights != null && highlights.containsKey(docId) && highlights.get(docId).containsKey("FILE_NM")) {
                        newDoc.put("FILE_NM", highlights.get(docId).get("FILE_NM").get(0));
                    } else {
                        newDoc.put("FILE_NM", doc.get("FILE_NM"));
                    }
                    
                    newDoc.put("FILE_UUID", fileUuid);
                    
                    if (doc.containsKey("_score")) {
                        newDoc.put("score", doc.get("_score"));
                    }
                    
                    // 2. Content 처리 (하이라이트 우선)
                    List<String> contents = null;
                    
                    // 하이라이트가 있으면 그것을 사용
                    if (highlights != null && highlights.containsKey(docId)) {
                        Map<String, List<String>> docHighlights = highlights.get(docId);
                        // paragraphs.content 하이라이트 확인
                        if (docHighlights.containsKey("paragraphs.content")) {
                            contents = docHighlights.get("paragraphs.content");
                        }
                    }
                    
                    // 하이라이트가 없으면 원본 content 사용 (기존 로직)
                    if (contents == null) {
                        Object paragraphsObj = doc.get("paragraphs");
                        if (paragraphsObj instanceof List) {
                            List<?> paragraphs = (List<?>) paragraphsObj;
                            contents = paragraphs.stream()
                                    .map(p -> {
                                        if (p instanceof Map) {
                                            return (String) ((Map<?, ?>) p).get("content");
                                        }
                                        return null;
                                    })
                                    .filter(s -> s != null)
                                    .limit(3) // 원본은 너무 길 수 있으므로 앞부분 3개만 (또는 적절히 조절)
                                    .collect(Collectors.toList());
                        }
                    }
                    
                    newDoc.put("content", contents);
                    
                    return newDoc;
                })
                .collect(Collectors.collectingAndThen(
                    Collectors.toMap(
                        doc -> doc.get("FILE_UUID").toString(),
                        doc -> doc,
                        (existing, replacement) -> {
                            Double existingScore = (Double) existing.getOrDefault("score", 0.0);
                            Double replacementScore = (Double) replacement.getOrDefault("score", 0.0);
                            return existingScore >= replacementScore ? existing : replacement;
                        },
                        java.util.LinkedHashMap::new
                    ),
                    map -> new java.util.ArrayList<>(map.values())
                ));

        Map<String, Object> response = new HashMap<>();
        response.put("total", processedDocuments.size());
        response.put("size", processedDocuments.size());
        response.put("results", processedDocuments);
        
        return response;
    }
}