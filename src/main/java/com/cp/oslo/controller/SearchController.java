package com.cp.oslo.controller;

import com.cp.oslo.client.EmbeddingClient;
import com.cp.oslo.util.AnalyzerConfigLoader; // AnalyzerConfigLoader import 추가
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
import java.util.Arrays;
import com.cp.oslo.dto.SearchResultDto; // SearchResultDto import 추가
import java.util.HashMap; // HashMap import 추가
import java.util.Collections; // Collections import 추가

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
    private final AnalyzerConfigLoader analyzerConfigLoader; // AnalyzerConfigLoader 주입

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

        SearchResultDto searchResult = openSearchService.search(indexName, query, operator, size); // 반환 타입 변경
        
        if ("file".equalsIgnoreCase(indexName)) {
            return ResponseEntity.ok(processFileResponse(searchResult, query));
        }
        
        Map<String, Map<String, List<String>>> highlightsMap = searchResult.getHighlights();

        List<Map<String, Object>> results = searchResult.getDocuments().stream() // documents 사용
                .map(source -> {
                    Map<String, Object> result = convertKeysToCamelCase(source);
                    result.remove("embedding"); // 임베딩 필드 제거
                    result.remove("_id"); // _id 필드 제거
                    result.remove("_score"); // _score 필드 제거 (중복 방지)
                    
                    // _score 필드 복구 (score로 변경)
                    if (source.containsKey("_score")) {
                        result.put("score", source.get("_score"));
                    }
                    
                    // 문서 ID 추출 (OpenSearchService에서 _id 필드 추가됨)
                    String docId = null;
                    if (source.get("_id") != null) docId = source.get("_id").toString();
                    else if (source.get("id") != null) docId = source.get("id").toString();
                    else if (source.get("UUID") != null) docId = source.get("UUID").toString();
                    else if (source.get("uuid") != null) docId = source.get("uuid").toString();
                    else if (source.get("MANUAL_UUID") != null) docId = source.get("MANUAL_UUID").toString(); // manual 인덱스용

                    // 하이라이트 정보 병합 (원본 필드 덮어쓰기)
                    if (docId != null && highlightsMap != null && highlightsMap.containsKey(docId)) {
                        Map<String, List<String>> docHighlights = highlightsMap.get(docId);
                        for (Map.Entry<String, List<String>> entry : docHighlights.entrySet()) {
                            String fieldName = entry.getKey();
                            List<String> fragments = entry.getValue();
                            if (fragments != null && !fragments.isEmpty()) {
                                // 필드명을 camelCase로 변환하여 원본 데이터의 키와 일치시킴 (예: TITLE -> title)
                                String camelCaseField = CaseUtils.toCamelCase(fieldName);
                                // 원본 필드 값을 하이라이트된 텍스트로 교체
                                result.put(camelCaseField, fragments.get(0));
                            }
                        }
                    }

                    return result;
                })
                .collect(Collectors.toList());

        Map<String, Object> responseData = new HashMap<>(); // Map.of는 불변 Map을 생성하므로 변경 가능한 Map 사용
        responseData.put("total", searchResult.getTotalHits());
        responseData.put("size", results.size());
        responseData.put("query", query);
        responseData.put("operator", operator);
        responseData.put("results", results);
        // responseData.put("highlights", searchResult.getHighlights()); // 별도 필드 제거

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
                    result.remove("_id"); // _id 필드 제거
                    result.remove("_score"); // _score 필드 제거
                    result.put("score", hit.score()); // _score를 score로 변경
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
            @RequestParam(required = false) String textFields,
            @RequestParam(required = false, defaultValue = "embedding") String vectorFieldName,
            @RequestParam String query,
            @RequestParam(required = false) Double textWeight,
            @RequestParam(required = false) Integer size) {
        
        // 파라미터가 없을 경우 DB(TB_CONFIG)에서 설정값 로드
        List<String> fieldList;
        if (textFields != null && !textFields.isBlank()) {
            fieldList = List.of(textFields.split(","));
        } else {
            fieldList = analyzerConfigLoader.loadSearchFields();
            if (fieldList.isEmpty()) {
                // DB에도 설정이 없으면 기본값 사용
                fieldList = List.of("TITLE", "CONTENTS"); 
            }
        }
        
        if (textWeight == null) {
            textWeight = analyzerConfigLoader.loadSearchWeight();
        }

        if (size == null) {
            size = analyzerConfigLoader.loadSearchSize();
        }

        log.info("하이브리드 검색 요청: index={}, fields={}, vectorField={}, query={}, textWeight={}, size={}", 
                indexName, fieldList, vectorFieldName, query, textWeight, size);

        if (textWeight != null && (textWeight < 0.0 || textWeight > 1.0)) {
            textWeight = Math.max(0.0, Math.min(textWeight, 1.0));
        }

        if (size != null && (size < 1 || size > 100)) {
            size = Math.max(1, Math.min(size, 100));
        }

        List<Double> queryVector = embeddingClient.embed(query);

        SearchResultDto searchResult = openSearchService.hybridSearch(
                indexName, fieldList, vectorFieldName, query, queryVector, textWeight, size);
        
        if ("file".equalsIgnoreCase(indexName)) {
            return ResponseEntity.ok(processFileResponse(searchResult, query));
        }
        
        List<Map<String, Object>> results = searchResult.getDocuments().stream()
                .map(source -> {
                    Map<String, Object> result = convertKeysToCamelCase(source);
                    result.remove("embedding");
                    result.remove("_id"); // _id 필드 제거
                    result.remove("_score"); // _score 필드 제거
                    
                    // _score 필드 복구 (score로 변경)
                    if (source.containsKey("_score")) {
                        result.put("score", source.get("_score"));
                    }

                    return result;
                })
                .collect(Collectors.toList());

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("total", searchResult.getTotalHits());
        responseData.put("size", results.size());
        responseData.put("query", query);
        responseData.put("textFields", fieldList);
        responseData.put("vectorField", vectorFieldName);
        responseData.put("textWeight", textWeight);
        responseData.put("vectorWeight", 1.0 - (textWeight != null ? textWeight : 0.5));
        responseData.put("results", results);

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

    private Map<String, Object> processFileResponse(SearchResultDto result, String query) {
        List<Map<String, Object>> processedDocuments = result.getDocuments().stream()
                .map(doc -> {
                    Map<String, Object> newDoc = new HashMap<>();
                    newDoc.put("fileNm", doc.get("FILE_NM"));
                    newDoc.put("fileUuid", doc.get("FILE_UUID"));
                    
                    if (doc.containsKey("_score")) {
                        newDoc.put("score", doc.get("_score"));
                    }
                    
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
                        doc -> doc.get("fileUuid").toString(),
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
        response.put("query", query);
        response.put("results", processedDocuments);
        
        return response;
    }
}
