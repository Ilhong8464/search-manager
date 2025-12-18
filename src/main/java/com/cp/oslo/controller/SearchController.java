package com.cp.oslo.controller;

import com.cp.oslo.client.EmbeddingClient;
import com.cp.oslo.util.AnalyzerConfigLoader;
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
import com.cp.oslo.dto.SearchResultDto;
import java.util.HashMap;
import java.util.Collections;

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
    private final AnalyzerConfigLoader analyzerConfigLoader;

    @org.springframework.beans.factory.annotation.Value("${embedding.rerank.enabled:false}")
    private boolean rerankEnabled;

    @org.springframework.beans.factory.annotation.Value("${embedding.rerank.window-size:50}")
    private int rerankWindowSize;

    /**
     * 검색 실행
     */
    @GetMapping("/{indexName}")
    public ResponseEntity<Map<String, Object>> search(
            @PathVariable String indexName,
            @RequestParam(required = false, defaultValue = "*") String query,
            @RequestParam(required = false, defaultValue = "OR") String operator,
            @RequestParam(required = false) Integer size) {

        if (size == null) {
            size = analyzerConfigLoader.loadSearchSize();
        }

        log.info("검색 요청: index={}, query={}, operator={}, size={}", indexName, query, operator, size);

        if (operator != null && !operator.equalsIgnoreCase("AND") && !operator.equalsIgnoreCase("OR")) {
            operator = "OR";
        }

        if (size != null && (size < 1 || size > 100)) {
            size = Math.max(1, Math.min(size, 100));
        }

        SearchResultDto searchResult = openSearchService.search(indexName, query, operator, size);

        if ("file".equalsIgnoreCase(indexName)) {
            return ResponseEntity.ok(processFileResponse(searchResult, query));
        }

        Map<String, Map<String, List<String>>> highlightsMap = searchResult.getHighlights();

        List<Map<String, Object>> results = searchResult.getDocuments().stream()
                .map(source -> {
                    Map<String, Object> result = convertKeysToCamelCase(source);
                    result.remove("embedding");
                    result.remove("_id");
                    result.remove("_score");

                    if (source.containsKey("_score")) {
                        result.put("score", source.get("_score"));
                    }

                    String docId = null;
                    if (source.get("_id") != null) docId = source.get("_id").toString();
                    else if (source.get("id") != null) docId = source.get("id").toString();
                    else if (source.get("UUID") != null) docId = source.get("UUID").toString();
                    else if (source.get("uuid") != null) docId = source.get("uuid").toString();
                    else if (source.get("MANUAL_UUID") != null) docId = source.get("MANUAL_UUID").toString();

                    if (docId != null && highlightsMap != null && highlightsMap.containsKey(docId)) {
                        Map<String, List<String>> docHighlights = highlightsMap.get(docId);
                        for (Map.Entry<String, List<String>> entry : docHighlights.entrySet()) {
                            String fieldName = entry.getKey();
                            List<String> fragments = entry.getValue();
                            if (fragments != null && !fragments.isEmpty()) {
                                String camelCaseField = CaseUtils.toCamelCase(fieldName);
                                result.put(camelCaseField, fragments.get(0));
                            }
                        }
                    }

                    return result;
                })
                .collect(Collectors.toList());

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("total", searchResult.getTotalHits());
        responseData.put("size", results.size());
        responseData.put("query", query);
        responseData.put("operator", operator);
        responseData.put("results", results);

        return ResponseEntity.ok(responseData);
    }

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
                    result.remove("_id");
                    result.remove("_score");
                    result.put("score", hit.score());
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

        List<String> fieldList;
        if (textFields != null && !textFields.isBlank()) {
            fieldList = List.of(textFields.split(","));
        } else {
            fieldList = analyzerConfigLoader.loadSearchFields();
            if (fieldList.isEmpty()) {
                fieldList = List.of("TITLE", "CONTENTS");
            }
        }

        if (textWeight == null) {
            textWeight = analyzerConfigLoader.loadSearchWeight();
        }

        if (size == null) {
            size = analyzerConfigLoader.loadSearchSize();
        }

        if (textWeight != null && (textWeight < 0.0 || textWeight > 1.0)) {
            textWeight = Math.max(0.0, Math.min(textWeight, 1.0));
        }

        if (size != null && (size < 1 || size > 100)) {
            size = Math.max(1, Math.min(size, 100));
        }

        // 리랭킹 적용 여부 판단
        boolean applyRerank = rerankEnabled && (size <= rerankWindowSize);
        int requestSize = applyRerank ? rerankWindowSize : size;

        log.info("하이브리드 검색 요청: index={}, query={}, rerank={}, size={}, requestSize={}",
                indexName, query, applyRerank, size, requestSize);

        List<Double> queryVector = embeddingClient.embed(query);

        // OpenSearch에서 1차 검색 수행
        SearchResultDto initialSearchResult = openSearchService.hybridSearch(
                indexName, fieldList, vectorFieldName, query, queryVector, textWeight, requestSize);

        SearchResultDto finalSearchResult = initialSearchResult; // effectively final을 위해 별도 변수 할당

        // 리랭킹 수행
        if (applyRerank && !initialSearchResult.getDocuments().isEmpty()) {
            try {
//                log.info("리랭킹 시작: 후보 문서 {}개", initialSearchResult.getDocuments().size());
                
                final Map<String, Map<String, List<String>>> highlightsForRerank = initialSearchResult.getHighlights();

                // 1. 리랭킹 입력용 텍스트 추출
                List<String> rerankInputs = initialSearchResult.getDocuments().stream()
                        .map(doc -> extractRerankText(indexName, doc, highlightsForRerank))
                        .collect(Collectors.toList());
                
                // 2. 리랭킹 API 호출
//                long rerankStartTime = System.currentTimeMillis();
                Map<String, Object> rerankResult = embeddingClient.rerank(query, rerankInputs);
                List<Integer> indices = (List<Integer>) rerankResult.get("indices");
                List<Double> scores = (List<Double>) rerankResult.get("scores");
//                long rerankEndTime = System.currentTimeMillis();
//                log.info("리랭킹 API 호출 소요 시간: {}ms", (rerankEndTime - rerankStartTime));
                
                // 3. 결과 재정렬 및 점수 업데이트
                List<Map<String, Object>> reorderedDocs = new java.util.ArrayList<>();
                for (int i = 0; i < indices.size(); i++) {
                    int originalIndex = indices.get(i);
                    if (originalIndex < initialSearchResult.getDocuments().size()) {
                        Map<String, Object> doc = initialSearchResult.getDocuments().get(originalIndex);
                        
                        // 점수 업데이트: _score 필드를 리랭킹 점수(Sigmoid 변환)로 덮어씀
                        double rawScore = scores.get(originalIndex);
                        doc.put("_score", sigmoid(rawScore));
                        
                        reorderedDocs.add(doc);
                    }
                }
                
                // 4. 상위 N개만 선택하여 finalSearchResult 구성
                int finalSize = Math.min(size, reorderedDocs.size());
                finalSearchResult = SearchResultDto.builder()
                        .totalHits(initialSearchResult.getTotalHits())
                        .documents(reorderedDocs.subList(0, finalSize))
                        .highlights(initialSearchResult.getHighlights())
                        .build();

//                log.info("리랭킹 완료: 상위 {}개 선택", finalSize);

            } catch (Exception e) {
                log.error("리랭킹 수행 중 오류 발생 (기존 결과 반환)", e);
                // 리랭킹 실패 시, initialSearchResult에서 요청된 size만큼만 잘라서 반환
                int finalSize = Math.min(size, initialSearchResult.getDocuments().size());
                finalSearchResult = SearchResultDto.builder()
                        .totalHits(initialSearchResult.getTotalHits())
                        .documents(initialSearchResult.getDocuments().subList(0, finalSize))
                        .highlights(initialSearchResult.getHighlights())
                        .build();
            }
        } else if (initialSearchResult.getDocuments().size() > size) {
             // 리랭킹 안 하는데 windowSize만큼 가져왔을 경우 대비
             int finalSize = Math.min(size, initialSearchResult.getDocuments().size());
             finalSearchResult = SearchResultDto.builder()
                        .totalHits(initialSearchResult.getTotalHits())
                        .documents(initialSearchResult.getDocuments().subList(0, finalSize))
                        .highlights(initialSearchResult.getHighlights())
                        .build();
        }

        if ("file".equalsIgnoreCase(indexName)) {
            return ResponseEntity.ok(processFileResponse(finalSearchResult, query));
        }

        final Map<String, Map<String, List<String>>> highlightsForProcessing = finalSearchResult.getHighlights();

        List<Map<String, Object>> results = finalSearchResult.getDocuments().stream()
                .map(source -> {
                    Map<String, Object> result = convertKeysToCamelCase(source);
                    result.remove("embedding");
                    result.remove("_id");
                    result.remove("_score");
                    
                    if (source.containsKey("_score")) {
                        result.put("score", source.get("_score"));
                    }

                    String docId = null;
                    if (source.get("_id") != null) docId = source.get("_id").toString();
                    else if (source.get("id") != null) docId = source.get("id").toString();
                    else if (source.get("UUID") != null) docId = source.get("UUID").toString();
                    else if (source.get("uuid") != null) docId = source.get("uuid").toString();
                    else if (source.get("MANUAL_UUID") != null) docId = source.get("MANUAL_UUID").toString();

                    if (docId != null && highlightsForProcessing != null && highlightsForProcessing.containsKey(docId)) {
                        Map<String, List<String>> docHighlights = highlightsForProcessing.get(docId);
                        for (Map.Entry<String, List<String>> entry : docHighlights.entrySet()) {
                            String fieldName = entry.getKey();
                            List<String> fragments = entry.getValue();
                            if (fragments != null && !fragments.isEmpty()) {
                                String camelCaseField = CaseUtils.toCamelCase(fieldName);
                                result.put(camelCaseField, fragments.get(0));
                            }
                        }
                    }

                    return result;
                })
                .collect(Collectors.toList());

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("total", finalSearchResult.getTotalHits());
        responseData.put("size", results.size());
        responseData.put("query", query);
        responseData.put("textFields", fieldList);
        responseData.put("vectorField", vectorFieldName);
        responseData.put("textWeight", textWeight);
        responseData.put("vectorWeight", 1.0 - (textWeight != null ? textWeight : 0.5));
        responseData.put("results", results);

        return ResponseEntity.ok(responseData);
    }

    /**
     * Sigmoid 함수: 실수 값을 0~1 사이로 변환
     */
    private double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    private Map<String, Object> convertKeysToCamelCase(Map<String, Object> source) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (source == null) return result;

        source.forEach((key, value) -> {
            String camelKey = CaseUtils.toCamelCase(key);
            result.put(camelKey, value);
        });
        return result;
    }

    private Map<String, Object> processFileResponse(SearchResultDto result, String query) {
        Map<String, Map<String, List<String>>> highlightsMap = result.getHighlights();

        List<Map<String, Object>> processedDocuments = result.getDocuments().stream()
                .map(doc -> {
                    Map<String, Object> newDoc = new HashMap<>();
                    newDoc.put("fileNm", doc.get("FILE_NM"));
                    newDoc.put("fileUuid", doc.get("FILE_UUID"));

                    if (doc.containsKey("_score")) {
                        newDoc.put("score", doc.get("_score"));
                    }

                    String docId = doc.get("_id") != null ? doc.get("_id").toString() : null;
                    List<String> highlightedContents = null;

                    if (docId != null && highlightsMap != null && highlightsMap.containsKey(docId)) {
                        Map<String, List<String>> docHighlights = highlightsMap.get(docId);
                        if (docHighlights.containsKey("paragraphs.content")) {
                            highlightedContents = docHighlights.get("paragraphs.content");
                        }
                    }

                    if (highlightedContents != null && !highlightedContents.isEmpty()) {
                        newDoc.put("content", highlightedContents);
                    } else {
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

    /**
     * 리랭킹을 위한 텍스트 추출 (하이라이트 우선)
     */
    private String extractRerankText(String indexName, Map<String, Object> doc, Map<String, Map<String, List<String>>> highlights) {
        String docId = doc.get("_id") != null ? doc.get("_id").toString() : "";
        StringBuilder sb = new StringBuilder();

        if (highlights != null && highlights.containsKey(docId)) {
            Map<String, List<String>> docHighlights = highlights.get(docId);

            if ("file".equalsIgnoreCase(indexName)) {
                if (docHighlights.containsKey("paragraphs.content")) {
                    for (String fragment : docHighlights.get("paragraphs.content")) {
                        sb.append(fragment.replaceAll("<[^>]*>", "")).append(" ");
                    }
                } else if (docHighlights.containsKey("FILE_NM")) {
                    sb.append(docHighlights.get("FILE_NM").get(0).replaceAll("<[^>]*>", "")).append(" ");
                }
            } else {
                if (docHighlights.containsKey("CONTENTS")) {
                    sb.append(docHighlights.get("CONTENTS").get(0).replaceAll("<[^>]*>", "")).append(" ");
                }
                if (docHighlights.containsKey("TITLE")) {
                    sb.append(docHighlights.get("TITLE").get(0).replaceAll("<[^>]*>", "")).append(" ");
                }
            }
        }

        if (sb.length() < 10) {
            if ("file".equalsIgnoreCase(indexName)) {
                Object paragraphsObj = doc.get("paragraphs");
                if (paragraphsObj instanceof List) {
                    List<?> paragraphs = (List<?>) paragraphsObj;
                    if (!paragraphs.isEmpty()) {
                        Object p = paragraphs.get(0);
                        if (p instanceof Map) {
                            String content = (String) ((Map<?, ?>) p).get("content");
                            if (content != null) {
                                sb.append(content).append(" ");
                            }
                        }
                    }
                }
            } else {
                if (doc.containsKey("TITLE")) sb.append(doc.get("TITLE")).append(" ");
                if (doc.containsKey("CONTENTS")) sb.append(doc.get("CONTENTS")).append(" ");
            }
        }

        String text = sb.toString().trim();
        if (text.length() > 500) { // 길이 제한 500으로 축소
            text = text.substring(0, 500);
        }
        return text;
    }

}
