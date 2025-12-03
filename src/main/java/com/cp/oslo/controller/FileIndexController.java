package com.cp.oslo.controller;

import com.cp.oslo.dto.SearchResultDto;
import com.cp.oslo.service.FileIndexingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/file-index")
@RequiredArgsConstructor
public class FileIndexController {

    private final FileIndexingService fileIndexingService;
    private final com.cp.oslo.util.AnalyzerConfigLoader analyzerConfigLoader;

    @PostMapping("/sync")
    public ResponseEntity<?> sync() {
        // 대량의 파일 처리 시 시간이 오래 걸릴 수 있으므로 비동기 처리를 고려해야 하지만,
        // 현재는 명시적 호출에 의한 동기 처리로 구현합니다.
        fileIndexingService.indexAllFiles();
        return ResponseEntity.ok(Map.of("message", "파일 인덱싱이 완료되었습니다."));
    }

    @DeleteMapping
    public ResponseEntity<?> deleteIndex() {
        boolean deleted = fileIndexingService.deleteIndex();
        return ResponseEntity.ok(Map.of("message", "파일 인덱스 삭제 완료", "deleted", deleted));
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
            size = analyzerConfigLoader.loadSearchSize();
            if (size == null) size = 10;
        }
        
        if (textWeight == null) {
            textWeight = analyzerConfigLoader.loadSearchWeight();
        }
        
        // Validate
        size = Math.max(1, Math.min(size, 100));
        if (textWeight != null) {
            textWeight = Math.max(0.0, Math.min(textWeight, 1.0));
        }

        SearchResultDto result = fileIndexingService.search(query, textWeight, size);
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
            size = analyzerConfigLoader.loadSearchSize();
            if (size == null) size = 10;
        }
        size = Math.max(1, Math.min(size, 100));

        SearchResultDto result = fileIndexingService.searchText(query, size);
        return ResponseEntity.ok(processResponse(result));
    }

    private Map<String, Object> processResponse(SearchResultDto result) {
        List<Map<String, Object>> processedDocuments = result.getDocuments().stream()
                .map(doc -> {
                    Map<String, Object> newDoc = new HashMap<>();
                    newDoc.put("FILE_NM", doc.get("FILE_NM"));
                    newDoc.put("FILE_UUID", doc.get("FILE_UUID"));
                    
                    if (doc.containsKey("_score")) {
                        newDoc.put("score", doc.get("_score"));
                    }
                    
                    // paragraphs 처리
                    Object paragraphsObj = doc.get("paragraphs");
                    if (paragraphsObj instanceof List) {
                        List<?> paragraphs = (List<?>) paragraphsObj;
                        List<String> contents = paragraphs.stream()
                                .map(p -> {
                                    if (p instanceof Map) {
                                        return (String) ((Map<?, ?>) p).get("content");
                                    }
                                    return null;
                                })
                                .filter(s -> s != null)
                                .collect(Collectors.toList());
                        newDoc.put("content", contents);
                    }
                    
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
        
