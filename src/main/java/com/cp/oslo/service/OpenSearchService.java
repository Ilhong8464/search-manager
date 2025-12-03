package com.cp.oslo.service;

import com.cp.oslo.config.VectorFieldConfig;
import com.cp.oslo.model.FieldDefinition;
import com.cp.oslo.model.IndexDefinition;
import com.cp.oslo.util.AnalyzerConfigLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.*;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.springframework.stereotype.Service;

import java.util.Map; // Map import 추가
import java.util.HashMap; // HashMap import 추가
import java.util.stream.Collectors; // Collectors import 추가
import java.util.List; // List import 추가
import com.cp.oslo.dto.SearchResultDto; // SearchResultDto import
import org.opensearch.client.opensearch.core.SearchRequest; // SearchRequest import
import org.opensearch.client.opensearch._types.query_dsl.Operator; // Operator import
import org.opensearch.client.opensearch.core.DeleteByQueryRequest; // DeleteByQueryRequest import
import org.opensearch.client.opensearch._types.FieldValue; // FieldValue import


/**
 * OpenSearch 인덱스 및 문서 관리 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenSearchService {

    private final OpenSearchClient openSearchClient;
    private final AnalyzerConfigLoader analyzerConfigLoader;
    private final VectorFieldConfig vectorFieldConfig;

    /**
     * 특정 필드 값이 허용된 목록에 포함되지 않는 문서들을 삭제합니다.
     * (예: DATA_TYPE이 [MANUAL, DOC]에 속하지 않는 문서 삭제)
     */
    public void deleteDocumentsNotInTypes(String indexName, String fieldName, List<String> allowedTypes) {
        try {
            if (!indexExists(indexName)) {
                return;
            }

            if (allowedTypes == null || allowedTypes.isEmpty()) {
                log.warn("허용된 타입 목록이 비어 있습니다. 삭제 작업을 건너뜁니다.");
                return;
            }

            // 쿼리: Must Not Terms (fieldName IN allowedTypes)
            // 즉, allowedTypes에 포함되지 않는 문서들을 찾아서 삭제
            DeleteByQueryRequest request = new DeleteByQueryRequest.Builder()
                    .index(indexName)
                    .query(q -> q
                            .bool(b -> b
                                    .mustNot(mn -> mn
                                            .terms(t -> t
                                                    .field(fieldName)
                                                    .terms(tt -> tt
                                                            .value(allowedTypes.stream()
                                                                    .map(FieldValue::of)
                                                                    .collect(Collectors.toList()))
                                                    )
                                            )
                                    )
                            )
                    )
                    .build();

            openSearchClient.deleteByQuery(request);
            log.info("인덱스 '{}'에서 허용되지 않은 타입의 문서를 삭제했습니다. (허용된 타입: {})", indexName, allowedTypes);

        } catch (Exception e) {
            log.error("문서 삭제(deleteByQuery) 실패", e);
            throw new RuntimeException("문서 삭제 실패", e);
        }
    }

    /**
     * 인덱스 생성
     */
    public boolean createIndex(IndexDefinition definition) {
        try {
            // 인덱스 존재 여부 확인
            if (indexExists(definition.getIndexName())) {
                log.warn("인덱스가 이미 존재합니다: {}", definition.getIndexName());
                return false;
            }

            // 매핑 생성 (YAML 벡터 필드 포함)
            Map<String, Property> properties = createMappingProperties(
                    definition.getFields(), 
                    definition.getIndexName()
            );

            // 인덱스 설정 생성 (analyzer 포함)
            IndexSettings indexSettings = createIndexSettings(
                    1, // 기본값 하드코딩 또는 definition에 추가 가능
                    1, // 기본값 하드코딩
                    definition
            );

            // 인덱스 생성 요청
            CreateIndexRequest createIndexRequest = CreateIndexRequest.of(c -> c
                    .index(definition.getIndexName())
                    .settings(indexSettings)
                    .mappings(m -> m.properties(properties))
            );

            openSearchClient.indices().create(createIndexRequest);
            log.info("인덱스 생성 완료: {}", definition.getIndexName());
            return true;

        } catch (Exception e) {
            log.error("인덱스 생성 실패: {}", definition.getIndexName(), e);
            throw new RuntimeException("인덱스 생성 실패", e);
        }
    }

    /**
     * 인덱스 삭제
     */
    public boolean deleteIndex(String indexName) {
        try {
            if (!indexExists(indexName)) {
                log.warn("인덱스가 존재하지 않습니다: {}", indexName);
                return false;
            }

            DeleteIndexRequest request = DeleteIndexRequest.of(d -> d.index(indexName));
            openSearchClient.indices().delete(request);
            log.info("인덱스 삭제 완료: {}", indexName);
            return true;

        } catch (Exception e) {
            log.error("인덱스 삭제 실패: {}", indexName, e);
            throw new RuntimeException("인덱스 삭제 실패", e);
        }
    }

    /**
     * 인덱스 존재 여부 확인
     */
    public boolean indexExists(String indexName) {
        try {
            ExistsRequest request = ExistsRequest.of(e -> e.index(indexName));
            return openSearchClient.indices().exists(request).value();
        } catch (Exception e) {
            log.error("인덱스 존재 여부 확인 실패: {}", indexName, e);
            return false;
        }
    }

    /**
     * 필드 목록에 KNN_VECTOR 타입이 포함되어 있는지 재귀적으로 확인
     */
    private boolean hasKnnVector(List<FieldDefinition> fields) {
        if (fields == null) return false;
        for (FieldDefinition field : fields) {
            if (field.getType() == FieldDefinition.FieldType.KNN_VECTOR) {
                return true;
            }
            if (field.getType() == FieldDefinition.FieldType.NESTED) {
                if (hasKnnVector(field.getSubFields())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Bulk 문서 인덱싱
     */
    public BulkIndexResult bulkIndex(String indexName, List<Map<String, Object>> documents) {
        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

            for (Map<String, Object> doc : documents) {
                String docId = doc.get("id") != null ? doc.get("id").toString() : null;
                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(indexName)
                                .id(docId)
                                .document(doc)
                        )
                );
            }

            BulkResponse result = openSearchClient.bulk(bulkBuilder.build());

            long successCount = 0;
            long failCount = 0;
            String firstErrorReason = null;

            if (result.errors()) {
                for (BulkResponseItem item : result.items()) {
                    if (item.error() != null) {
                        failCount++;
                        // 첫 번째 에러만 저장
                        if (firstErrorReason == null) {
                            firstErrorReason = item.error().reason();
                        }
                    } else {
                        successCount++;
                    }
                }
                // 실패가 있을 때만 첫 번째 에러만 로그
                if (failCount > 0) {
                    log.warn("Bulk 인덱싱 중 {}건 실패 (첫 번째 에러: {})", failCount, firstErrorReason);
                }
            } else {
                successCount = documents.size();
            }

            return new BulkIndexResult(successCount, failCount, result.errors());

        } catch (Exception e) {
            log.error("Bulk 인덱싱 실패", e);
            throw new RuntimeException("Bulk 인덱싱 실패", e);
        }
    }

    /**
     * 단건 문서 인덱싱
     */
    public boolean indexDocument(String indexName, Map<String, Object> document) {
        try {
            String docId = null;
            if (document.get("id") != null) {
                docId = document.get("id").toString();
            } else if (document.get("UUID") != null) {
                docId = document.get("UUID").toString();
            } else if (document.get("uuid") != null) {
                docId = document.get("uuid").toString();
            }

            final String finalDocId = docId;

            openSearchClient.index(i -> i
                    .index(indexName)
                    .id(finalDocId)
                    .document(document)
            );

            return true;
        } catch (Exception e) {
            log.error("문서 인덱싱 실패: index={}, doc={}", indexName, document, e);
            throw new RuntimeException("문서 인덱싱 실패", e);
        }
    }
    
    // 검색 메서드들은 변경 없음 (String indexName 사용)
    public SearchResultDto search(String indexName, String query, String operator, Integer size) {
        // ... (기존 코드 유지)
        try {
            // operator 기본값 및 검증
            String defaultOperator = (operator != null && "AND".equalsIgnoreCase(operator)) ? "AND" : "OR";

            // size 기본값 및 제한 (최소 1, 최대 100, 기본 10)
            int resultSize = 10;
            if (size != null) {
                resultSize = Math.max(1, Math.min(size, 100));
            }
            
            // file 인덱스 특수 처리 (Nested 구조 전문 검색)
            if ("file".equalsIgnoreCase(indexName)) {
                return searchNestedText(indexName, "paragraphs", "content", query, resultSize);
            }
            
            // Determine target fields based on indexName
            List<String> targetFields;
            if ("call".equals(indexName)) {
                targetFields = List.of("QUESTION", "ANSWER");
            } else if ("manual-qna".equals(indexName)) {
                targetFields = List.of("TITLE", "CONTENTS");
            } else if ("manual".equals(indexName)) { // manual 인덱스에 CAT_NM 필드 추가
                targetFields = List.of("TITLE", "CONTENTS", "CAT_NM");
            } else if ("doc-notice".equals(indexName)) {
                targetFields = List.of("DOC_NM", "CONTENTS");
            } else if ("unified".equals(indexName)) {
                targetFields = List.of("TITLE", "CONTENTS");
            } else { 
                targetFields = List.of("TITLE", "CONTENTS");
            }    

            SearchRequest.Builder searchRequestBuilder = new SearchRequest.Builder()
                    .index(indexName)
                    .size(resultSize)
                    .query(q -> q
                            .multiMatch(m -> m
                                    .fields(targetFields)
                                    .query(query)
                                    .operator("AND".equalsIgnoreCase(defaultOperator)
                                            ? Operator.And
                                            : Operator.Or
                                    )
                                    .type(org.opensearch.client.opensearch._types.query_dsl.TextQueryType.CrossFields)
                            )
                    );

            // manual 인덱스에만 하이라이팅 적용
            if ("manual".equals(indexName)) {
                searchRequestBuilder.highlight(h -> h
                        .fields("TITLE", f -> f
                                .preTags("<b>")
                                .postTags("</b>")
                                .fragmentSize(100)
                                .numberOfFragments(1)
                        )
                        .fields("CONTENTS", f -> f
                                .preTags("<b>")
                                .postTags("</b>")
                                .fragmentSize(100)
                                .numberOfFragments(1)
                        )
                        .fields("CAT_NM", f -> f // CAT_NM 필드도 하이라이팅에 포함
                                .preTags("<b>")
                                .postTags("</b>")
                                .fragmentSize(100)
                                .numberOfFragments(1)
                        )
                );
            }
            
            // search 인덱스 하이라이팅 적용
            if ("unified".equals(indexName)) {
                searchRequestBuilder.highlight(h -> h
                        .fields("TITLE", f -> f
                                .preTags("<b>")
                                .postTags("</b>")
                                .fragmentSize(100)
                                .numberOfFragments(1)
                        )
                        .fields("CONTENTS", f -> f
                                .preTags("<b>")
                                .postTags("</b>")
                                .fragmentSize(100)
                                .numberOfFragments(1)
                        )
                );
            }

            SearchResponse<Map> response = openSearchClient.search(searchRequestBuilder.build(), Map.class);
            log.info("OpenSearch response: {} hits", response.hits().total().value());

            List<Map<String, Object>> documents = response.hits().hits().stream()
                    .map(hit -> {
                        Map<String, Object> source = new HashMap<>(hit.source());
                        if (source != null) {
                            source.put("_id", hit.id());
                            source.put("_score", hit.score());
                        }
                        // 하이라이트 정보를 문서 자체에 병합하지 않고 별도로 관리 (SearchResultDto에)
                        return source;
                    })
                    .collect(Collectors.toList());
            
            // 하이라이트 결과 파싱
            Map<String, Map<String, List<String>>> highlights = new HashMap<>();
            response.hits().hits().forEach(hit -> {
                if (hit.highlight() != null && !hit.highlight().isEmpty()) {
                    highlights.put(hit.id(), hit.highlight());
                }
            });

            return SearchResultDto.builder()
                    .totalHits(response.hits().total().value())
                    .documents(documents)
                    .highlights(highlights)
                    .build();

        } catch (Exception e) {
            log.error("검색 실패", e);
            throw new RuntimeException("검색 실패", e);
        }
    }

    public SearchResponse<Map> vectorSearch(String indexName, String vectorFieldName, List<Double> queryVector, Integer k) {
        // ... (기존 코드 유지)
        try {
            int resultSize = 10;
            if (k != null) {
                resultSize = Math.max(1, Math.min(k, 100));
            }
            final int finalK = resultSize;
            float[] vectorArray = new float[queryVector.size()];
            for (int i = 0; i < queryVector.size(); i++) {
                vectorArray[i] = queryVector.get(i).floatValue();
            }
            SearchResponse<Map> response = openSearchClient.search(s -> s
                            .index(indexName)
                            .size(finalK)
                            .query(q -> q
                                    .knn(knn -> knn
                                            .field(vectorFieldName)
                                            .vector(vectorArray)
                                            .k(finalK)
                                    )
                            ),
                    Map.class
            );
            return response;
        } catch (Exception e) {
            throw new RuntimeException("벡터 검색 실패", e);
        }
    }

    public SearchResultDto hybridSearch(String indexName, List<String> textFields, String vectorFieldName, 
                                           String queryText, List<Double> queryVector, 
                                           Double textWeight, Integer size) {
        
        // file 인덱스 특수 처리 (Nested 구조)
        if ("file".equalsIgnoreCase(indexName)) {
            return searchNestedHybrid(indexName, "paragraphs", "content", "embedding", queryText, queryVector, textWeight, size);
        }

        try {
            double textScore = (textWeight != null) ? Math.max(0.0, Math.min(textWeight, 1.0)) : 0.5;
            double vectorScore = 1.0 - textScore;
            int resultSize = 10;
            if (size != null) {
                resultSize = Math.max(1, Math.min(size, 100));
            }
            final int finalSize = resultSize;
            final double finalTextScore = textScore;
            final double finalVectorScore = vectorScore;
            float[] vectorArray = new float[queryVector.size()];
            for (int i = 0; i < queryVector.size(); i++) {
                vectorArray[i] = queryVector.get(i).floatValue();
            }

            SearchRequest.Builder searchRequestBuilder = new SearchRequest.Builder()
                    .index(indexName)
                    .size(finalSize)
                    .query(q -> q
                            .bool(b -> b
                                    .should(sh -> sh
                                            .multiMatch(mm -> mm
                                                    .fields(textFields)
                                                    .query(queryText)
                                                    .boost((float) finalTextScore)
                                            )
                                    )
                                    .should(sh -> sh
                                            .knn(knn -> knn
                                                    .field(vectorFieldName)
                                                    .vector(vectorArray)
                                                    .k(finalSize)
                                                    .boost((float) finalVectorScore)
                                            )
                                    )
                            )
                    );

            SearchResponse<Map> response = openSearchClient.search(searchRequestBuilder.build(), Map.class);

            List<Map<String, Object>> documents = response.hits().hits().stream()
                    .map(hit -> {
                        Map<String, Object> source = new HashMap<>(hit.source());
                        if (source != null) {
                            source.put("_id", hit.id());
                            source.put("_score", hit.score());
                        }
                        return source;
                    })
                    .collect(Collectors.toList());
            
            return SearchResultDto.builder()
                    .totalHits(response.hits().total().value())
                    .documents(documents)
                    .highlights(null)
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("하이브리드 검색 실패", e);
        }
    }

    /**
     * Nested 필드 대상 하이브리드 검색
     */
    public SearchResultDto searchNestedHybrid(String indexName, String nestedPath, 
                                              String textField, String vectorField,
                                              String queryText, List<Double> queryVector,
                                              Double textWeight, Integer size) {
        try {
            double textScore = (textWeight != null) ? Math.max(0.0, Math.min(textWeight, 1.0)) : 0.5;
            double vectorScore = 1.0 - textScore;
            
            float[] vectorArray = new float[queryVector.size()];
            for (int i = 0; i < queryVector.size(); i++) {
                vectorArray[i] = queryVector.get(i).floatValue();
            }

            SearchRequest request = new SearchRequest.Builder()
                    .index(indexName)
                    .size(size)
                    .query(q -> q
                            .bool(b -> b
                                    .should(s -> s
                                            .nested(n -> n
                                                    .path(nestedPath)
                                                    .query(nq -> nq
                                                            .match(m -> m
                                                                    .field(nestedPath + "." + textField)
                                                                    .query(FieldValue.of(queryText))
                                                                    .boost((float) textScore)
                                                            )
                                                    )
                                                    .scoreMode(org.opensearch.client.opensearch._types.query_dsl.ChildScoreMode.Max)
                                            )
                                    )
                                    .should(s -> s
                                            .nested(n -> n
                                                    .path(nestedPath)
                                                    .query(nq -> nq
                                                            .knn(k -> k
                                                                    .field(nestedPath + "." + vectorField)
                                                                    .vector(vectorArray)
                                                                    .k(size)
                                                                    .boost((float) vectorScore)
                                                            )
                                                    )
                                                    .scoreMode(org.opensearch.client.opensearch._types.query_dsl.ChildScoreMode.Max)
                                            )
                                    )
                            )
                    )
                    .build();

            SearchResponse<Map> response = openSearchClient.search(request, Map.class);
            
            List<Map<String, Object>> documents = response.hits().hits().stream()
                    .map(hit -> {
                        Map<String, Object> source = new HashMap<>();
                        if (hit.source() != null) {
                            source.putAll(hit.source());
                        }
                        source.put("_id", hit.id());
                        source.put("_score", hit.score());
                        return source;
                    })
                    .collect(Collectors.toList());

            return SearchResultDto.builder()
                    .totalHits(response.hits().total().value())
                    .documents(documents)
                    .build();

        } catch (Exception e) {
            log.error("Nested 하이브리드 검색 실패", e);
            throw new RuntimeException("Nested 하이브리드 검색 실패", e);
        }
    }

    /**
     * Nested 필드 대상 전문 검색 (텍스트만)
     */
    public SearchResultDto searchNestedText(String indexName, String nestedPath, 
                                            String textField, String queryText, int size) {
        try {
            SearchRequest request = new SearchRequest.Builder()
                    .index(indexName)
                    .size(size)
                    .query(q -> q
                            .nested(n -> n
                                    .path(nestedPath)
                                    .query(nq -> nq
                                            .match(m -> m
                                                    .field(nestedPath + "." + textField)
                                                    .query(FieldValue.of(queryText))
                                            )
                                    )
                                    .scoreMode(org.opensearch.client.opensearch._types.query_dsl.ChildScoreMode.Max)
                            )
                    )
                    .build();

            SearchResponse<Map> response = openSearchClient.search(request, Map.class);
            
            List<Map<String, Object>> documents = response.hits().hits().stream()
                    .map(hit -> {
                        Map<String, Object> source = new HashMap<>();
                        if (hit.source() != null) {
                            source.putAll(hit.source());
                        }
                        source.put("_id", hit.id());
                        source.put("_score", hit.score());
                        return source;
                    })
                    .collect(Collectors.toList());

            return SearchResultDto.builder()
                    .totalHits(response.hits().total().value())
                    .documents(documents)
                    .build();

        } catch (Exception e) {
            log.error("Nested 전문 검색 실패", e);
            throw new RuntimeException("Nested 전문 검색 실패", e);
        }
    }

    /**
     * 단건 문서 삭제
     */
    public boolean deleteDocument(String indexName, String docId) {
        try {
            org.opensearch.client.opensearch.core.DeleteRequest request = 
                org.opensearch.client.opensearch.core.DeleteRequest.of(d -> d
                    .index(indexName)
                    .id(docId)
            );
            org.opensearch.client.opensearch.core.DeleteResponse response = openSearchClient.delete(request);
            return response.result() == org.opensearch.client.opensearch._types.Result.Deleted;
        } catch (Exception e) {
            log.error("문서 삭제 실패: index={}, id={}", indexName, docId, e);
            throw new RuntimeException("문서 삭제 실패", e);
        }
    }

    /**
     * 인덱스 설정 생성 (custom analyzer 포함)
     */
    private IndexSettings createIndexSettings(Integer numberOfShards, Integer numberOfReplicas, IndexDefinition definition) {
        List<String> synonyms = analyzerConfigLoader.loadSynonyms();
        List<String> stopwords = analyzerConfigLoader.loadStopwords();
        
        IndexSettings.Builder builder = new IndexSettings.Builder()
                .numberOfShards(String.valueOf(numberOfShards))
                .numberOfReplicas(String.valueOf(numberOfReplicas))
                .analysis(a -> a
                        .filter("synonym_filter", tf -> tf
                                .definition(tfd -> tfd.synonym(syn -> syn.synonyms(synonyms))))
                        .filter("stopword_filter", tf -> tf
                                .definition(tfd -> tfd.stop(stop -> stop.stopwords(stopwords))))
                        .analyzer("nori_custom", an -> an
                                .custom(ca -> ca
                                        .tokenizer("nori_tokenizer")
                                        .filter("lowercase", "synonym_filter", "stopword_filter", "nori_readingform")
                                )
                        )
                );

        if (vectorFieldConfig.hasVectorField(definition.getIndexName()) || hasKnnVector(definition.getFields())) {
            builder.knn(true);
        }
        return builder.build();
    }

    /**
     * 필드 매핑을 OpenSearch Property로 변환
     */
    private Map<String, Property> createMappingProperties(List<FieldDefinition> fields, String indexName) {
        Map<String, Property> properties = new HashMap<>();

        for (FieldDefinition field : fields) {
            String fieldName = field.getEffectiveFieldName();
            Property property = convertToProperty(field);
            properties.put(fieldName, property);
        }

        // YAML 설정의 벡터 필드 추가
        if (vectorFieldConfig.hasVectorField(indexName)) {
            VectorFieldConfig.VectorField vectorField = vectorFieldConfig.getVectorField(indexName);
            Property vectorProperty = Property.of(p -> p.knnVector(knn -> 
                knn.dimension(vectorField.getDimension())
                   .method(method -> method
                       .name("hnsw")
                       .spaceType(vectorField.getSpaceType())
                       .engine(vectorField.getEngine())
                   )
            ));
            properties.put(vectorField.getTargetField(), vectorProperty);
        }

        return properties;
    }

    /**
     * FieldDefinition을 OpenSearch Property로 변환
     */
    private Property convertToProperty(FieldDefinition field) {
        return switch (field.getType()) {
            case TEXT -> Property.of(p -> p.text(t -> {
                if (field.getAnalyzer() != null) {
                    String analyzer = "nori".equals(field.getAnalyzer()) 
                            ? "nori_custom" 
                            : field.getAnalyzer();
                    t.analyzer(analyzer);
                }
                return t;
            }));
            case KEYWORD -> Property.of(p -> p.keyword(k -> k));
            case INTEGER -> Property.of(p -> p.integer(i -> i));
            case LONG -> Property.of(p -> p.long_(l -> l));
            case DOUBLE -> Property.of(p -> p.double_(d -> d));
            case FLOAT -> Property.of(p -> p.float_(f -> f));
            case BOOLEAN -> Property.of(p -> p.boolean_(b -> b));
            case DATE -> Property.of(p -> p.date(d -> d));
            case KNN_VECTOR -> Property.of(p -> p.knnVector(knn -> {
                int dimension = field.getDimension() != null ? field.getDimension() : 768;
                return knn.dimension(dimension)
                          .method(method -> method.name("hnsw").spaceType("cosinesimil").engine("lucene"));
            }));
            case NESTED -> Property.of(p -> p.nested(n -> {
                if (field.getSubFields() != null) {
                    Map<String, Property> subProps = new HashMap<>();
                    for (FieldDefinition sub : field.getSubFields()) {
                        subProps.put(sub.getEffectiveFieldName(), convertToProperty(sub));
                    }
                    n.properties(subProps);
                }
                return n;
            }));
            default -> Property.of(p -> p.text(t -> t));
        };
    }

    /**
     * Bulk 인덱싱 결과
     */
    public record BulkIndexResult(long successCount, long failCount, boolean hasErrors) {
    }
}
