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
     * 실제 동기화 로직
     */
    private SyncHistory executeSync(IndexDefinition definition) {
        log.info("========================================");
        log.info("동기화 시작: {} (테이블: {})", definition.getIndexName(), definition.getSourceTableName());
        log.info("========================================");

        // 동기화 이력 생성
        SyncHistory history = SyncHistory.builder()
                .indexName(definition.getIndexName())
                .startTime(LocalDateTime.now())
                .status(SyncHistory.SyncStatus.RUNNING)
                .build();
        // 주의: 기존 SyncHistory에는 indexConfigId가 FK로 있었으나, 이제는 제거되어야 함.
        // 임시로 indexConfigId는 null 또는 가짜 값을 넣거나, 엔티티 수정 필요.
        // 여기서는 일단 엔티티 필드가 남아있다면 에러가 날 수 있으므로, 
        // SyncHistory 엔티티도 수정했다고 가정하거나, null을 허용해야 함.
        // (리팩토링 범위에 포함됨)
        history = syncHistoryRepository.save(history);

        try {
            // 인덱스가 없으면 생성
            if (!openSearchService.indexExists(definition.getIndexName())) {
                log.info("인덱스 생성 중...");
                openSearchService.createIndex(definition);
            }

            // 데이터베이스에서 데이터 조회
            log.info("데이터 조회 중...");
            List<Map<String, Object>> allDocuments = fetchDataFromDatabase(definition);
            long totalRecords = allDocuments.size();

            log.info("총 {}건 조회 완료 - 배치 처리 시작", totalRecords);

            // Batch로 나누어 임베딩 생성 및 인덱싱
            long successCount = 0;
            long failCount = 0;
            int totalBatches = (int) Math.ceil((double) allDocuments.size() / BATCH_SIZE);
            int currentBatch = 0;

            for (int i = 0; i < allDocuments.size(); i += BATCH_SIZE) {
                currentBatch++;
                int endIndex = Math.min(i + BATCH_SIZE, allDocuments.size());
                List<Map<String, Object>> batch = allDocuments.subList(i, endIndex);

                // 1. 임베딩 생성 (배치 단위)
                enrichDocumentsWithEmbedding(definition.getIndexName(), batch);

                // 2. OpenSearch 인덱싱
                OpenSearchService.BulkIndexResult result = openSearchService.bulkIndex(
                        definition.getIndexName(),
                        batch
                );

                successCount += result.successCount();
                failCount += result.failCount();

                // 진행률 로그
                double progress = (double) endIndex / totalRecords * 100;
                // 임베딩 시간이 오래 걸리므로 모든 배치마다 로그 출력
                log.info("진행률: {}/{} ({}) | 성공: {} | 실패: {}",
                        endIndex, totalRecords, String.format("%.1f%%", progress), successCount, failCount);
            }

            // 동기화 완료 처리
            history.setRecordsProcessed(totalRecords);
            history.setRecordsSucceeded(successCount);
            history.setRecordsFailed(failCount);

            SyncHistory.SyncStatus status = failCount == 0
                    ? SyncHistory.SyncStatus.SUCCESS
                    : (successCount > 0 ? SyncHistory.SyncStatus.PARTIAL : SyncHistory.SyncStatus.FAILED);

            history.complete(status, null);

            // 마지막 동기화 시간 업데이트 (DB 상태 테이블)
            updateLastSyncState(definition.getIndexName(), status);

            log.info("========================================");
            log.info("동기화 완료: {}", definition.getIndexName());
            log.info("총 처리: {}건 | 성공: {}건 | 실패: {}건 | 상태: {}", 
                    totalRecords, successCount, failCount, status);
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
     * 데이터베이스에서 데이터 조회
     */
    private List<Map<String, Object>> fetchDataFromDatabase(IndexDefinition definition) {
        try {
            List<FieldDefinition> fields = definition.getFields();
            if (fields.isEmpty()) {
                throw new IllegalStateException("필드 정의가 없습니다");
            }

            String selectColumns = fields.stream()
                    .map(FieldDefinition::getSourceColumn)
                    .collect(Collectors.joining(", "));

            String sql = String.format("SELECT %s FROM %s", selectColumns, definition.getSourceTableName());

            log.debug("실행 SQL: {}", sql);

            List<Map<String, Object>> documents = jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> document = new HashMap<>();
                for (FieldDefinition field : fields) {
                    Object value = rs.getObject(field.getSourceColumn());
                    if (value != null) {
                        document.put(field.getEffectiveFieldName(), value);
                    }
                }
                return document;
            });

            // 문서 ID (_id) 설정
            String idColumn = definition.getIdColumn();
            FieldDefinition idField = fields.stream()
                    .filter(f -> f.getSourceColumn().equalsIgnoreCase(idColumn))
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
            } else {
                log.warn("인덱스 '{}'의 ID 컬럼 '{}'에 대한 필드 정의를 찾을 수 없습니다.", definition.getIndexName(), idColumn);
            }

            return documents;

        } catch (Exception e) {
            log.error("데이터베이스 조회 실패: {}", definition.getSourceTableName(), e);
            throw new RuntimeException("데이터베이스 조회 실패", e);
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

        log.info("단건 동기화 시작: index={}, uuid={}", indexName, uuid);

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