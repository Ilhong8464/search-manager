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
    private final FileIndexingService fileIndexingService;

    private static final int BATCH_SIZE = 500;

    /**
     * 전체 동기화 실행 (인덱스 이름으로)
     */
    public SyncHistory syncIndex(String indexName) {
        // 1. 활성화 여부 확인 (YML 설정 기반)
        if (!isIndexEnabled(indexName)) {
            log.warn("인덱스가 비활성화되어 있어 동기화를 건너뜁니다: {}", indexName);
            return null;
        }

        // 2. File 인덱스 특수 처리
        if ("file".equalsIgnoreCase(indexName)) {
            return syncFileIndex();
        }

        // 3. 인덱스 정의 조회 (코드 기반)
        IndexDefinition definition = indexRegistry.get(indexName);
        if (definition == null) {
            throw new IllegalArgumentException("알 수 없는 인덱스입니다: " + indexName);
        }
        
        return executeSync(definition);
    }

    private SyncHistory syncFileIndex() {
        if (!isFileConfigEnabled()) {
            log.info("파일 인덱싱 건너뜀: 파일 인덱스가 비활성화 상태입니다.");
            return null;
        }

        log.info("========================================");
        log.info("동기화 시작: file");
        log.info("========================================");

        SyncHistory history = SyncHistory.builder()
                .indexName("file")
                .startTime(LocalDateTime.now())
                .status(SyncHistory.SyncStatus.RUNNING)
                .build();
        history = syncHistoryRepository.save(history);

        try {
            fileIndexingService.indexAllFiles();

            history.complete(SyncHistory.SyncStatus.SUCCESS, null);
            updateLastSyncState("file", SyncHistory.SyncStatus.SUCCESS);

            log.info("========================================");
            log.info("동기화 완료: file");
            log.info("========================================");

        } catch (Exception e) {
            log.error("========================================");
            log.error("동기화 실패: file", e);
            log.error("========================================");
            history.complete(SyncHistory.SyncStatus.FAILED, e.getMessage());
            updateLastSyncState("file", SyncHistory.SyncStatus.FAILED);
        }

        return syncHistoryRepository.save(history);
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
            }

            if ("unified".equals(definition.getIndexName())) {
                List<String> enabledTypes = fetchEnabledDataTypes();
                if (enabledTypes.isEmpty()) {
                    // 모든 데이터 타입이 비활성화된 경우: unified 인덱스의 모든 데이터를 삭제
                    log.info("모든 데이터 타입이 비활성화되어, unified 인덱스의 모든 데이터를 삭제합니다.");
                    // 인덱스가 존재하면 삭제 후 재생성 (이렇게 하면 인덱스가 비워짐)
                    if (openSearchService.indexExists(definition.getIndexName())) {
                        openSearchService.deleteIndex(definition.getIndexName());
                        openSearchService.createIndex(definition); // 빈 인덱스 재생성
                    }
                } else {
                    log.info("비활성화된 데이터 정리 중... (활성화된 타입: {})", enabledTypes);
                    openSearchService.deleteDocumentsNotInTypes(definition.getIndexName(), "DATA_TYPE", enabledTypes);
                }

                long totalProcessed = 0;
                long successCount = 0;
                long failCount = 0;

                log.info("데이터 조회 및 인덱싱 시작 (Batch Size: {})", BATCH_SIZE);

                for (String dataType : enabledTypes) {
                    String lastId = null; // 각 데이터 타입별로 마지막 ID 추적
                    long typeProcessed = 0;
                    log.info("----> 데이터 타입 동기화 시작: {}", dataType);

                    while (true) {
                        long loopStart = System.currentTimeMillis();

                        // 1. 배치 데이터 조회
                        List<Map<String, Object>> batch = fetchUnifiedBatchFromDatabase(dataType, lastId, BATCH_SIZE);
                        long afterFetch = System.currentTimeMillis();

                        if (batch.isEmpty()) {
                            break;
                        }

                        // 다음 배치를 위한 lastId 업데이트
                        lastId = (String) batch.get(batch.size() - 1).get("UUID");

                        // 2. 임베딩 생성
                        enrichDocumentsWithEmbedding(definition.getIndexName(), batch);
                        long afterEmbedding = System.currentTimeMillis();

                        // 3. OpenSearch 인덱싱
                        OpenSearchService.BulkIndexResult result = openSearchService.bulkIndex(
                                definition.getIndexName(),
                                batch
                        );
                        long afterIndex = System.currentTimeMillis();

                        successCount += result.successCount();
                        failCount += result.failCount();
                        totalProcessed += batch.size();
                        typeProcessed += batch.size();

                        log.info("구간 소요시간 - DB조회: {}ms, 임베딩: {}ms, ES색인: {}ms | 총: {}ms",
                                (afterFetch - loopStart),
                                (afterEmbedding - afterFetch),
                                (afterIndex - afterEmbedding),
                                (afterIndex - loopStart));

                        log.info("진행 중... 처리: {}건 (현재 타입: {} | 총: {}건 | 성공: {} | 실패: {})",
                                typeProcessed, dataType, totalProcessed, successCount, failCount);
                    }
                    log.info("<---- 데이터 타입 동기화 완료: {} (총 {}건)", dataType, typeProcessed);
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

            } else { // unified 인덱스가 아닌 경우 기존 로직 유지
                long totalProcessed = 0;
                long successCount = 0;
                long failCount = 0;
                int offset = 0;
                
                log.info("데이터 조회 및 인덱싱 시작 (Batch Size: {})", BATCH_SIZE);

                while (true) {
                    long loopStart = System.currentTimeMillis();

                    // 1. 배치 데이터 조회
                    List<Map<String, Object>> batch = fetchBatchFromDatabase(definition, BATCH_SIZE, offset);
                    long afterFetch = System.currentTimeMillis();
                    
                    if (batch.isEmpty()) {
                        break;
                    }

                    // 2. 임베딩 생성
                    enrichDocumentsWithEmbedding(definition.getIndexName(), batch);
                    long afterEmbedding = System.currentTimeMillis();

                    // 3. OpenSearch 인덱싱
                    OpenSearchService.BulkIndexResult result = openSearchService.bulkIndex(
                            definition.getIndexName(),
                            batch
                    );
                    long afterIndex = System.currentTimeMillis();

                    successCount += result.successCount();
                    failCount += result.failCount();
                    totalProcessed += batch.size();
                    offset += BATCH_SIZE; // 다음 배치를 위해 오프셋 증가

                    log.info("구간 소요시간 - DB조회: {}ms, 임베딩: {}ms, ES색인: {}ms | 총: {}ms",
                            (afterFetch - loopStart),
                            (afterEmbedding - afterFetch),
                            (afterIndex - afterEmbedding),
                            (afterIndex - loopStart));

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
            }

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
     * TB_CONFIG 테이블에서 활성화된(CONFIG_VALUE='Y') 검색 컬렉션 타입 조회 (File 제외)
     */
    private List<String> fetchEnabledDataTypes() {
        try {
            // KEY_PATH가 'System.SearchEngine.Collection.'으로 시작하고 CONFIG_VALUE가 'Y'인 항목 조회
            // CONFIG_KEY를 대문자로 변환하여 반환 (예: Call -> CALL)
            // 단, 'FILE'은 별도 인덱스로 관리되므로 제외
            String sql = "SELECT UPPER(CONFIG_KEY) FROM TB_CONFIG " +
                         "WHERE KEY_PATH LIKE 'System.SearchEngine.Collection.%' " +
                         "AND CONFIG_VALUE = 'Y' " +
                         "AND UPPER(CONFIG_KEY) != 'FILE'";
            
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
        // 1. 인덱스 활성화 여부 확인 (YML 설정 기반)
        if (!isIndexEnabled(indexName)) {
            log.warn("단건 동기화 건너뜀: 인덱스 '{}'가 YML 설정에 의해 비활성화되어 있습니다.", indexName);
            return;
        }

        // 2. File 인덱스 특수 처리
        if ("file".equalsIgnoreCase(indexName)) {
            fileIndexingService.indexFileByUuid(uuid);
            return;
        }

        IndexDefinition definition = indexRegistry.get(indexName);
        if (definition == null) {
             throw new IllegalArgumentException("알 수 없는 인덱스입니다: " + indexName);
        }

        try {
            Map<String, Object> document = fetchDocumentByUuid(definition, uuid);
            if (document == null) {
                // 문서가 DB에 없으면 OpenSearch에서도 삭제
                log.warn("데이터베이스에서 문서를 찾을 수 없습니다: uuid={}. OpenSearch에서 삭제 시도.", uuid);
                openSearchService.deleteDocument(indexName, uuid); // OpenSearch에서 삭제
                return;
            }

            // 3. unified 인덱스이고 TB_CONFIG에서 비활성화된 타입이면 인덱싱하지 않고 삭제
            if ("unified".equals(indexName)) {
                List<String> enabledTypes = fetchEnabledDataTypes(); // TB_CONFIG에서 활성화된 타입 목록 가져옴
                String documentDataType = (String) document.get("DATA_TYPE"); // 문서의 DATA_TYPE 필드
                
                if (documentDataType == null || !enabledTypes.contains(documentDataType.toUpperCase())) {
                    log.warn("단건 동기화 건너뜀: unified 인덱스의 문서 '{}' (DATA_TYPE: {})가 TB_CONFIG에서 비활성화되어 있습니다. OpenSearch에서 삭제 시도.", uuid, documentDataType);
                    openSearchService.deleteDocument(indexName, uuid); // OpenSearch에서 삭제
                    return;
                }
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

    /**
     * 단건 문서 삭제
     */
    public void deleteDocument(String indexName, String uuid) {
        if (!openSearchService.indexExists(indexName)) {
             log.warn("인덱스가 존재하지 않아 삭제를 건너뜁니다: {}", indexName);
             return;
        }
        
        boolean deleted = openSearchService.deleteDocument(indexName, uuid);
        if (deleted) {
            log.info("문서 삭제 완료: index={}, uuid={}", indexName, uuid);
        } else {
            log.warn("문서를 찾을 수 없거나 삭제에 실패했습니다: index={}, uuid={}", indexName, uuid);
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

    /**
     * unified 인덱스를 위해 데이터 타입별로 배치 데이터 조회 (Keyset Paging)
     * DATA_TYPE, UUID, TITLE, CONTENTS 필드만 추출
     */
    private List<Map<String, Object>> fetchUnifiedBatchFromDatabase(String dataType, String lastId, int limit) {
        String sourceTable = getTableNameForDataType(dataType);
        String idColumn = getIdColumnForDataType(dataType);
        String titleColumn = getTitleColumnForDataType(dataType);
        String contentsColumn = getContentsColumnForDataType(dataType);

        if (sourceTable == null || idColumn == null || titleColumn == null || contentsColumn == null) {
            log.warn("Unified 인덱스 '{}({})'의 필드 매핑이 정의되지 않았습니다. 동기화를 건너뜀.", dataType, sourceTable);
            return Collections.emptyList();
        }

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(String.format("SELECT '%s' AS DATA_TYPE, %s AS UUID, %s AS TITLE, %s AS CONTENTS FROM %s",
                dataType, idColumn, titleColumn, contentsColumn, sourceTable));

        List<Object> params = new ArrayList<>();

        if (lastId != null && !lastId.isEmpty()) {
            sqlBuilder.append(String.format(" WHERE %s > ?", idColumn));
            params.add(lastId);
        }
        sqlBuilder.append(String.format(" ORDER BY %s ASC LIMIT ?", idColumn));
        params.add(limit);

        String sql = sqlBuilder.toString();
        
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> document = new HashMap<>();
                document.put("DATA_TYPE", rs.getString("DATA_TYPE"));
                document.put("UUID", rs.getString("UUID"));
                document.put("TITLE", rs.getString("TITLE"));
                document.put("CONTENTS", rs.getString("CONTENTS"));
                document.put("id", rs.getString("UUID")); // OpenSearch _id 필드에 매핑
                return document;
            }, params.toArray());
        } catch (Exception e) {
            log.error("Unified 인덱스 '{}' 데이터 조회 실패 (lastId: {}): {}", dataType, lastId, e.getMessage(), e);
            throw new RuntimeException("Unified 인덱스 데이터 조회 실패", e);
        }
    }

    private String getTableNameForDataType(String dataType) {
        return switch (dataType) {
            case "CALL" -> "uvw_call";
            case "MANUAL" -> "uvw_manual";
            case "NOTICE" -> "tb_doc"; // uvw_doc_notice가 존재하지 않으므로 tb_doc 직접 사용
            default -> null;
        };
    }

    private String getIdColumnForDataType(String dataType) {
        return switch (dataType) {
            case "CALL" -> "CALL_UUID";
            case "MANUAL" -> "MANUAL_UUID";
            case "NOTICE" -> "DOC_UUID";
            default -> null;
        };
    }

    private String getTitleColumnForDataType(String dataType) {
        return switch (dataType) {
            case "CALL" -> "QUESTION";
            case "MANUAL" -> "TITLE";
            case "NOTICE" -> "DOC_NM";
            default -> null;
        };
    }

    private String getContentsColumnForDataType(String dataType) {
        return switch (dataType) {
            case "CALL" -> "ANSWER";
            case "MANUAL", "NOTICE" -> "CONTENTS";
            default -> null;
        };
    }

    private void enrichDocumentsWithEmbedding(String indexName, List<Map<String, Object>> documents) {
         if (vectorFieldConfig.hasVectorField(indexName) && embeddingClient.isAvailable()) {
            VectorFieldConfig.VectorField vectorField = vectorFieldConfig.getVectorField(indexName);
            String[] sourceFields = vectorField.getSourceField().split(",");
            
            List<String> textsToEmbed = new ArrayList<>();
            List<Map<String, Object>> docsToEmbed = new ArrayList<>();

            // 1. 임베딩할 텍스트 추출 및 수집
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
                
                // 텍스트가 있는 경우만 처리 대상에 포함
                if (!text.isEmpty()) {
                    textsToEmbed.add(text);
                    docsToEmbed.add(doc);
                }
            }

            // 2. 배치 임베딩 요청 및 결과 매핑
            if (!textsToEmbed.isEmpty()) {
                try {
                    List<List<Double>> embeddings = embeddingClient.embedBatch(textsToEmbed);
                    
                    if (embeddings.size() != docsToEmbed.size()) {
                        log.warn("요청한 텍스트 수({})와 반환된 임베딩 수({})가 일치하지 않습니다.", 
                                textsToEmbed.size(), embeddings.size());
                    }

                    for (int i = 0; i < embeddings.size(); i++) {
                        if (i < docsToEmbed.size()) {
                            docsToEmbed.get(i).put(vectorField.getTargetField(), embeddings.get(i));
                        }
                    }
                } catch (Exception e) {
                    log.error("배치 임베딩 생성 중 오류 발생: index={}", indexName, e);
                    // 실패 시 개별 문서는 임베딩 없이 진행됨 (또는 필요 시 재시도 로직 추가)
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
     * 모든 활성화된 인덱스를 삭제 후 재생성(Re-index)
     * 스케줄러 등에서 주기적으로 클린 인덱싱을 위해 사용
     */
    public void reindexAllEnabledIndexes() {
        indexRegistry.getDefinitions().keySet().forEach(indexName -> {
            if (isIndexEnabled(indexName)) {
                // File 인덱스의 경우 별도 설정 확인
                if ("file".equalsIgnoreCase(indexName) && !isFileConfigEnabled()) {
                    return;
                }

                try {
                    log.info("인덱스 재설정(삭제 후 생성) 시작: {}", indexName);
                    // 1. 인덱스 삭제
                    if (openSearchService.indexExists(indexName)) {
                        openSearchService.deleteIndex(indexName);
                    }
                    
                    // 2. 인덱스 동기화 (생성 및 데이터 주입)
                    syncIndex(indexName);
                    
                } catch (Exception e) {
                    log.error("인덱스 재설정 실패: {}", indexName, e);
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

    /**
     * 파일 인덱스 실행 여부를 DB 설정(tb_config)에서 조회
     */
    private boolean isFileConfigEnabled() {
        try {
            String sql = "SELECT config_value FROM tb_config WHERE key_path = 'System.SearchEngine.Collection.File'";
            List<String> results = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("config_value"));

            if (results.isEmpty()) {
                log.warn("파일 인덱스 설정(System.SearchEngine.Collection.File)이 DB에 없습니다. 기본값(N) 처리합니다.");
                return false;
            }

            return "Y".equalsIgnoreCase(results.get(0));
        } catch (Exception e) {
            log.error("파일 인덱스 설정 조회 중 오류 발생", e);
            return false;
        }
    }
}