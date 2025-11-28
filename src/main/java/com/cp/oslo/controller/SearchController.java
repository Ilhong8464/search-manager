package com.cp.oslo.controller;

import com.cp.oslo.client.EmbeddingClient;
import com.cp.oslo.service.OpenSearchService;
import com.cp.oslo.util.CaseUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 검색 API 컨트롤러
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Slf4j
public class SearchController {

    private final OpenSearchService openSearchService;
    private final EmbeddingClient embeddingClient;

    /**
     * 검색 실행
     */
    @GetMapping("/{indexName}")
    public ResponseEntity<Map<String, Object>> search(
            @PathVariable String indexName,
            @RequestParam(required = false, defaultValue = "*") String query,
            @RequestParam(required = false, defaultValue = "OR") String operator,
            @RequestParam(required = false, defaultValue = "10") Integer size) {
        
        log.info("검색 요청: index={}, query={}, operator={}, size={}", indexName, query, operator, size);

        if (operator != null && !operator.equalsIgnoreCase("AND") && !operator.equalsIgnoreCase("OR")) {
            operator = "OR";
        }

        if (size != null && (size < 1 || size > 100)) {
            size = Math.max(1, Math.min(size, 100));
        }

        SearchResponse<Map> response = openSearchService.search(indexName, query, operator, size);
        
        List<Map<String, Object>> results = response.hits().hits().stream()
                .map(hit -> {
                    Map<String, Object> source = (Map<String, Object>) hit.source();
                    Map<String, Object> result = convertKeysToCamelCase(source);
                    result.remove("embedding");
                    return result;
                })
                .collect(Collectors.toList());

        Map<String, Object> responseData = Map.of(
                "total", response.hits().total().value(),
                "size", results.size(),
                "query", query,
                "operator", operator,
                "results", results
        );

        return ResponseEntity.ok(responseData);
    }

    @GetMapping("/{indexName}/exists")
    public ResponseEntity<Map<String, Boolean>> checkIndexExists(@PathVariable String indexName) {
        boolean exists = openSearchService.indexExists(indexName);
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    @GetMapping("/vector/{indexName}")
    public ResponseEntity<Map<String, Object>> vectorSearch(
            @PathVariable String indexName,
            @RequestParam(required = false, defaultValue = "embedding") String vectorFieldName,
            @RequestParam String query,
            @RequestParam(required = false, defaultValue = "10") Integer k) {
        
        log.info("벡터 검색 요청: index={}, vectorField={}, query={}, k={}", indexName, vectorFieldName, query, k);

        if (k != null && (k < 1 || k > 100)) {
            k = Math.max(1, Math.min(k, 100));
        }

        List<Double> queryVector = embeddingClient.embed(query);

        SearchResponse<Map> response = openSearchService.vectorSearch(indexName, vectorFieldName, queryVector, k);
        
        List<Map<String, Object>> results = response.hits().hits().stream()
                .map(hit -> {
                    Map<String, Object> source = (Map<String, Object>) hit.source();
                    Map<String, Object> result = convertKeysToCamelCase(source);
                    result.remove("embedding");
                    result.put("_score", hit.score());
                    return result;
                })
                .collect(Collectors.toList());

        Map<String, Object> responseData = Map.of(
                "total", response.hits().total().value(),
                "size", results.size(),
                "query", query,
                "vectorField", vectorFieldName,
                "results", results
        );

        return ResponseEntity.ok(responseData);
    }

    @GetMapping("/hybrid/{indexName}")
    public ResponseEntity<Map<String, Object>> hybridSearch(
            @PathVariable String indexName,
            @RequestParam String textFields,
            @RequestParam(required = false, defaultValue = "embedding") String vectorFieldName,
            @RequestParam String query,
            @RequestParam(required = false, defaultValue = "0.5") Double textWeight,
            @RequestParam(required = false, defaultValue = "10") Integer size) {
        
        log.info("하이브리드 검색 요청: index={}, textFields={}, vectorField={}, query={}, textWeight={}, size={}", 
                indexName, textFields, vectorFieldName, query, textWeight, size);

        if (textWeight != null && (textWeight < 0.0 || textWeight > 1.0)) {
            textWeight = Math.max(0.0, Math.min(textWeight, 1.0));
        }

        if (size != null && (size < 1 || size > 100)) {
            size = Math.max(1, Math.min(size, 100));
        }

        List<String> fieldList = List.of(textFields.split(","));
        List<Double> queryVector = embeddingClient.embed(query);

        SearchResponse<Map> response = openSearchService.hybridSearch(
                indexName, fieldList, vectorFieldName, query, queryVector, textWeight, size);
        
        List<Map<String, Object>> results = response.hits().hits().stream()
                .map(hit -> {
                    Map<String, Object> source = (Map<String, Object>) hit.source();
                    Map<String, Object> result = convertKeysToCamelCase(source);
                    result.remove("embedding");
                    result.put("_score", hit.score());
                    return result;
                })
                .collect(Collectors.toList());

        Map<String, Object> responseData = Map.of(
                "total", response.hits().total().value(),
                "size", results.size(),
                "query", query,
                "textFields", fieldList,
                "vectorField", vectorFieldName,
                "textWeight", textWeight,
                "vectorWeight", 1.0 - textWeight,
                "results", results
        );

        return ResponseEntity.ok(responseData);
    }

    private Map<String, Object> convertKeysToCamelCase(Map<String, Object> source) {
        return source.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> CaseUtils.toCamelCase(entry.getKey()),
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue, // 중복 키 발생 시 기존 값 유지
                        java.util.LinkedHashMap::new // 순서 유지
                ));
    }
}
