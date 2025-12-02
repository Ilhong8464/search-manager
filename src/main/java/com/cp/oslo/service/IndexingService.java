package com.cp.oslo.service;

import com.cp.oslo.client.EmbeddingClient;
import com.cp.oslo.config.IndexRegistry;
import com.cp.oslo.config.SearchIndexProperties;
import com.cp.oslo.config.VectorFieldConfig;
import com.cp.oslo.domain.IndexState;
import com.cp.oslo.domain.SyncHistory;
import com.cp.oslo.model.FieldDefinition;
import com.cp.oslo.model.IndexDefinition;
import com.cp.oslo.repository.IndexStateRepository;
import com.cp.oslo.repository.SyncHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 데이터베이스 테이블을 OpenSearch로 인덱싱하는 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {

    private final IndexRegistry indexRegistry;
    private final SearchIndexProperties indexProperties;
    
    private final IndexStateRepository indexStateRepository;
    private final SyncHistoryRepository syncHistoryRepository;
    
    private final OpenSearchService openSearchService;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;
    private final VectorFieldConfig vectorFieldConfig;

    private static final int BATCH_SIZE = 1000;

    /**
     * 전체 동기화 실행 (인덱스 이름으로)
     */
    public SyncHistory syncIndex(String indexName) {
        // 1. 인덱스 정의 조회 (코드 기반)
        IndexDefinition definition = indexRegistry.get(indexName);
        if (definition == null) {
            throw new IllegalArgumentException("알 수 없는 인덱스입니다: " + indexName);
        }

        // 2. 활성화 여부 확인 (YML 설정 기반)
        if (!isIndexEnabled(indexName)) {
            log.warn("인덱스가 비활성화되어 있어 동기화를 건너뜁니다: {}", indexName);
            return null;
        }
        
        return executeSync(definition);
    }

    /**
     * 실제 동기화 로직 (Batch Processing 적용)
     */
    private SyncHistory executeSync(IndexDefinition definition) {
        log.info("========================================");
        log.info("동기화 시작: {} (테이블: {})", definition.getIndexName(), definition.getSourceTableName());
        log.info("========================================");

        SyncHistory history = SyncHistory.builder()
                .indexName(definition.getIndexName())
                .startTime(LocalDateTime.now())
                .status(SyncHistory.SyncStatus.RUNNING)
                .build();
        history = syncHistoryRepository.save(history);

        try {
            // 인덱스가 없으면 생성
            if (!openSearchService.indexExists(definition.getIndexName())) {
                log.info("인덱스 생성 중...");
                openSearchService.createIndex(definition);
            } else if ("unified".equals(definition.getIndexName())) {
                // unified 인덱스이고 이미 존재하는 경우: 비활성화된 데이터 타입 삭제 (TB_CONFIG 기준)
                List<String> enabledTypes = fetchEnabledDataTypes();
                if (!enabledTypes.isEmpty()) {
                    log.info("비활성화된 데이터 정리 중... (활성화된 타입: {})", enabledTypes);
                    openSearchService.deleteDocumentsNotInTypes(definition.getIndexName(), "DATA_TYPE", enabledTypes);
                }
            }

            long totalProcessed = 0;
            long successCount = 0;
            long failCount = 0;
            int offset = 0;
            
            log.info("데이터 조회 및 인덱싱 시작 (Batch Size: {})", BATCH_SIZE);

            while (true) {
                // 배치 데이터 조회
                List<Map<String, Object>> batch = fetchBatchFromDatabase(definition, BATCH_SIZE, offset);
                
                if (batch.isEmpty()) {
                    break;
                }

                // 1. 임베딩 생성
                enrichDocumentsWithEmbedding(definition.getIndexName(), batch);

                // 2. OpenSearch 인덱싱
                OpenSearchService.BulkIndexResult result = openSearchService.bulkIndex(
                        definition.getIndexName(),
                        batch
                );

                successCount += result.successCount();
                failCount += result.failCount();
                totalProcessed += batch.size();
                offset += BATCH_SIZE; // 다음 배치를 위해 오프셋 증가

                log.info("진행 중... 처리: {}건 | 성공: {} | 실패: {} (현재 오프셋: {})", 
                        totalProcessed, successCount, failCount, offset);
            }

            // 동기화 완료 처리
            history.setRecordsProcessed(totalProcessed);
            history.setRecordsSucceeded(successCount);
            history.setRecordsFailed(failCount);

            SyncHistory.SyncStatus status = failCount == 0
                    ? SyncHistory.SyncStatus.SUCCESS
                    : (successCount > 0 ? SyncHistory.SyncStatus.PARTIAL : SyncHistory.SyncStatus.FAILED);

            history.complete(status, null);
            updateLastSyncState(definition.getIndexName(), status);

            log.info("========================================");
            log.info("동기화 완료: {}", definition.getIndexName());
            log.info("총 처리: {}건 | 성공: {}건 | 실패: {}건 | 상태: {}", 
                    totalProcessed, successCount, failCount, status);
            log.info("========================================");

        } catch (Exception e) {
            log.error("========================================");
            log.error("동기화 실패: {}", definition.getIndexName(), e);
            log.error("========================================");
            history.complete(SyncHistory.SyncStatus.FAILED, e.getMessage());
            updateLastSyncState(definition.getIndexName(), SyncHistory.SyncStatus.FAILED);
        }

        return syncHistoryRepository.save(history);
    }

    /**
     * 마지막 동기화 상태 업데이트
     */
    private void updateLastSyncState(String indexName, SyncHistory.SyncStatus status) {
        IndexState state = indexStateRepository.findById(indexName)
                .orElse(IndexState.builder().indexName(indexName).build());
        
        state.setLastSyncTime(LocalDateTime.now());
        state.setLastSyncStatus(status.name());
        indexStateRepository.save(state);
    }

    /**
     * 데이터베이스에서 배치 단위로 데이터 조회 (Paging)
     */
    private List<Map<String, Object>> fetchBatchFromDatabase(IndexDefinition definition, int limit, int offset) {
        try {
            List<FieldDefinition> fields = definition.getFields();
            if (fields.isEmpty()) {
                throw new IllegalStateException("필드 정의가 없습니다");
            }

            String selectColumns = fields.stream()
                    .map(FieldDefinition::getSourceColumn)
                    .collect(Collectors.joining(", "));
            
            // 정렬 기준 컬럼 (ID 컬럼 우선)
            String idColumn = definition.getIdColumn();
            if (idColumn == null || idColumn.isEmpty()) {
                idColumn = fields.get(0).getSourceColumn();
            }

            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append(String.format("SELECT %s FROM %s", selectColumns, definition.getSourceTableName()));
            
            List<Object> params = new ArrayList<>();

            // unified 인덱스인 경우 TB_CONFIG 기반 필터링 적용
            if ("unified".equals(definition.getIndexName())) {
                List<String> enabledTypes = fetchEnabledDataTypes();
                if (enabledTypes.isEmpty()) {
                    log.info("활성화된 검색 컬렉션(TB_CONFIG)이 없습니다. 동기화를 중단합니다.");
                    return Collections.emptyList();
                }
                
                String inClause = enabledTypes.stream()
                        .map(type -> "?")
                        .collect(Collectors.joining(", "));
                
                // Collation 충돌 방지를 위해 CONVERT 사용
                sqlBuilder.append(String.format(" WHERE CONVERT(DATA_TYPE USING utf8mb4) IN (%s)", inClause));
                params.addAll(enabledTypes);
            }

            // ORDER BY 및 LIMIT/OFFSET 추가
            sqlBuilder.append(String.format(" ORDER BY %s ASC LIMIT ? OFFSET ?", idColumn));
            params.add(limit);
            params.add(offset);

            String sql = sqlBuilder.toString();
            
            List<Map<String, Object>> documents = jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> document = new HashMap<>();
                for (FieldDefinition field : fields) {
                    Object value = rs.getObject(field.getSourceColumn());
                    if (value != null) {
                        document.put(field.getEffectiveFieldName(), value);
                    }
                }
                return document;
            }, params.toArray());

            // 문서 ID (_id) 설정
            final String targetIdColumn = idColumn; // lambda용 final 변수
            FieldDefinition idField = fields.stream()
                    .filter(f -> f.getSourceColumn().equalsIgnoreCase(targetIdColumn))
                    .findFirst()
                    .orElse(null);
            
            if (idField != null) {
                String idKey = idField.getEffectiveFieldName();
                for (Map<String, Object> doc : documents) {
                    Object idVal = doc.get(idKey);
                    if (idVal != null) {
                        doc.put("id", idVal.toString());
                    }
                }
            }

            return documents;

        } catch (Exception e) {
            log.error("데이터베이스 배치 조회 실패: {} (Offset: {})", definition.getSourceTableName(), offset, e);
            throw new RuntimeException("데이터베이스 배치 조회 실패", e);
        }
    }

    /**
     * TB_CONFIG 테이블에서 활성화된(CONFIG_VALUE='Y') 검색 컬렉션 타입 조회
     */
    private List<String> fetchEnabledDataTypes() {
        try {
            // KEY_PATH가 'System.SearchEngine.Collection.'으로 시작하고 CONFIG_VALUE가 'Y'인 항목 조회
            // CONFIG_KEY를 대문자로 변환하여 반환 (예: Call -> CALL)
            String sql = "SELECT UPPER(CONFIG_KEY) FROM TB_CONFIG " +
                         "WHERE KEY_PATH LIKE 'System.SearchEngine.Collection.%' " +
                         "AND CONFIG_VALUE = 'Y'";
            
            return jdbcTemplate.queryForList(sql, String.class);
        } catch (Exception e) {
            log.error("TB_CONFIG 조회 실패", e);
            return Collections.emptyList();
        }
    }

    /**
     * 단건 문서 동기화 (실시간 인덱싱)
     */
    public void syncDocument(String indexName, String uuid) {
        IndexDefinition definition = indexRegistry.get(indexName);
        if (definition == null) {
             throw new IllegalArgumentException("알 수 없는 인덱스입니다: " + indexName);
        }

//        log.info("단건 동기화 시작: index={}, uuid={}", indexName, uuid);

        try {
            Map<String, Object> document = fetchDocumentByUuid(definition, uuid);
            if (document == null) {
                log.warn("데이터베이스에서 문서를 찾을 수 없습니다: uuid={}", uuid);
                return;
            }

            // 문서 ID 설정
            document.put("id", uuid);

            enrichDocumentsWithEmbedding(indexName, Collections.singletonList(document));
            openSearchService.indexDocument(indexName, document);
            log.info("단건 동기화 완료: index={}, uuid={}", indexName, uuid);

        } catch (Exception e) {
            log.error("단건 동기화 실패: index={}, uuid={}", indexName, uuid, e);
            throw new RuntimeException("단건 동기화 실패", e);
        }
    }

    private Map<String, Object> fetchDocumentByUuid(IndexDefinition definition, String uuid) {
        // ... 단건 조회 로직 ...
        String idColumn = definition.getIdColumn(); // IndexDefinition에서 명확한 ID 컬럼 획득
        if (idColumn == null) {
             idColumn = "UUID"; // Fallback
        }
        
        String selectColumns = definition.getFields().stream()
                .map(FieldDefinition::getSourceColumn)
                .collect(Collectors.joining(", "));
        
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", 
                selectColumns, definition.getSourceTableName(), idColumn);
        
        try {
            List<Map<String, Object>> results = jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> doc = new HashMap<>();
                for (FieldDefinition f : definition.getFields()) {
                    Object val = rs.getObject(f.getSourceColumn());
                    if (val != null) {
                        doc.put(f.getEffectiveFieldName(), val);
                    }
                }
                return doc;
            }, uuid);
            
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            throw new RuntimeException("단건 조회 실패", e);
        }
    }

    private void enrichDocumentsWithEmbedding(String indexName, List<Map<String, Object>> documents) {
         // ... (기존 로직과 동일, 다만 FieldDefinition 등 사용 불필요, Map 조작이므로 동일)
         if (vectorFieldConfig.hasVectorField(indexName) && embeddingClient.isAvailable()) {
            VectorFieldConfig.VectorField vectorField = vectorFieldConfig.getVectorField(indexName);
            String[] sourceFields = vectorField.getSourceField().split(",");
            
            for (Map<String, Object> doc : documents) {
                StringBuilder textBuilder = new StringBuilder();
                for (String field : sourceFields) {
                    Object value = doc.get(field.trim());
                    if (value != null) {
                        if (textBuilder.length() > 0) textBuilder.append(" ");
                        textBuilder.append(value.toString());
                    }
                }
                String text = textBuilder.toString().trim();
                if (!text.isEmpty()) {
                    try {
                        List<Double> embedding = embeddingClient.embed(text);
                        doc.put(vectorField.getTargetField(), embedding);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        }
    }

    // --- 스케줄러용 메서드 ---

    /**
     * 모든 활성화된 인덱스 동기화
     */
    public void syncAllEnabledIndexes() {
        indexRegistry.getDefinitions().keySet().forEach(indexName -> {
            if (isIndexEnabled(indexName)) {
                try {
                    syncIndex(indexName);
                } catch (Exception e) {
                    log.error("동기화 실패: {}", indexName, e);
                }
            }
        });
    }

    /**
     * 주기적 동기화 체크
     */
    @Transactional
    public void checkAndSyncIntervalIndexes() {
        Map<String, SearchIndexProperties.IndexSettings> settingsMap = indexProperties.getIndexes();
        if (settingsMap == null) return;

        LocalDateTime now = LocalDateTime.now();

        settingsMap.forEach((indexName, settings) -> {
            // enabled=true AND autoSync=true 인지 확인
            if (settings.isEnabled() && settings.isAutoSync()) {
                // 마지막 동기화 시간 확인 (DB)
                IndexState state = indexStateRepository.findById(indexName).orElse(null);
                
                boolean shouldSync = false;
                if (state == null || state.getLastSyncTime() == null) {
                    shouldSync = true;
                } else {
                    int interval = settings.getSyncIntervalMinutes();
                    if (interval > 0) {
                        LocalDateTime nextSyncTime = state.getLastSyncTime().plusMinutes(interval);
                        if (nextSyncTime.isBefore(now)) {
                            shouldSync = true;
                        }
                    }
                }

                if (shouldSync) {
                    log.info("주기적 동기화 실행: {}", indexName);
                    try {
                        syncIndex(indexName);
                    } catch (Exception e) {
                        log.error("주기적 동기화 실패: {}", indexName, e);
                    }
                }
            }
        });
    }

    private boolean isIndexEnabled(String indexName) {
        if (indexProperties.getIndexes() == null) return false;
        SearchIndexProperties.IndexSettings settings = indexProperties.getIndexes().get(indexName);
        return settings != null && settings.isEnabled();
    }
}