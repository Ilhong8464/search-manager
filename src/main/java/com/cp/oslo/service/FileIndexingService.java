package com.cp.oslo.service;

import com.cp.oslo.client.EmbeddingClient;
import com.cp.oslo.config.IndexRegistry;
import com.cp.oslo.config.SearchIndexProperties; // SearchIndexProperties import 추가
import com.cp.oslo.domain.TbFile;
import com.cp.oslo.repository.TbFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileIndexingService {

    private final TbFileRepository tbFileRepository;
    private final OpenSearchService openSearchService;
    private final EmbeddingClient embeddingClient;
    private final IndexRegistry indexRegistry;
    private final SearchIndexProperties searchIndexProperties; // SearchIndexProperties 주입

    @Value("${logging.file.path:/app/upload}")
    private String containerUploadPath;

    // localPathPrefix는 SearchIndexProperties에서 가져옵니다.

    @Transactional(readOnly = true)
    public void indexAllFiles() {
        log.info("파일 전체 인덱싱 시작");

        // 1. 인덱스 생성 (없으면)
        if (!openSearchService.indexExists("file")) {
            openSearchService.createIndex(indexRegistry.get("file"));
        }

        // 2. 파일 조회
        List<TbFile> files = tbFileRepository.findIndexableFiles();
        log.info("인덱싱 대상 파일 수: {}", files.size());

        int success = 0;
        int fail = 0;

        for (TbFile file : files) {
            try {
                indexFile(file);
                success++;
            } catch (Exception e) {
                log.error("파일 인덱싱 실패 (ID: {}, Name: {}): {}", file.getFileId(), file.getFileNm(), e.getMessage());
                fail++;
            }
        }

        log.info("파일 인덱싱 완료. 성공: {}, 실패: {}", success, fail);
    }

    public void indexFileByUuid(String uuid) {
        TbFile file = tbFileRepository.findByFileUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("해당 UUID의 파일을 찾을 수 없습니다: " + uuid));
        
        try {
            // 인덱스가 없으면 생성
            if (!openSearchService.indexExists("file")) {
                openSearchService.createIndex(indexRegistry.get("file"));
            }
            indexFile(file);
            log.info("단건 파일 인덱싱 완료: {}", uuid);
        } catch (Exception e) {
            log.error("단건 파일 인덱싱 실패: {}", uuid, e);
            throw new RuntimeException("파일 인덱싱 실패", e);
        }
    }

    private void indexFile(TbFile file) throws Exception {
        // 1. 실제 파일 경로 계산
        String localPathPrefix = searchIndexProperties.getIndexes().get("file").getLocalPathPrefix();
        String savedPath = file.getSavedFilePath();
        String realPath;
        
        log.info("인덱싱 처리 중: [파일경로] {}/{}", file.getSavedFilePath(), file.getSavedFileNm());
        
        if (savedPath.startsWith(localPathPrefix)) {
            realPath = savedPath.replace(localPathPrefix, containerUploadPath);
        } else {
            // 매칭되지 않으면 경로의 끝부분과 containerUploadPath를 결합 시도 (fallback)
            // 예: /some/other/path/image/2025/10 -> /app/upload/image/2025/10 라고 가정하기 어려움.
            // 그냥 원본 사용 시도하거나, URL 기반 추론
             if (file.getUrl() != null && file.getUrl().startsWith("/")) {
                 realPath = containerUploadPath + file.getUrl();
                 // URL이 파일명까지 포함하므로 디렉토리 경로와 파일명 분리 필요 없음?
                 // 하지만 URL은 웹 경로고 SAVED_FILE_PATH는 물리 경로임.
                 // SAVED_FILE_NM이 실제 저장된 파일명.
                 // 일단 savedPath 그대로 사용 시도
                 realPath = savedPath; 
             } else {
                 realPath = savedPath;
             }
        }

        File targetFile = new File(realPath, file.getSavedFileNm());

        if (!targetFile.exists()) {
            // fallback: URL 구조를 보고 경로 유추
            // URL이 빈 문자열이 아니고 유효한 경우에만 시도
            if (file.getUrl() != null && !file.getUrl().trim().isEmpty()) {
                 File fallbackFile = new File(containerUploadPath + file.getUrl()); // URL에는 파일명 포함됨
                 if (fallbackFile.exists() && !fallbackFile.isDirectory()) {
                     targetFile = fallbackFile;
                 }
            }
        }

        if (!targetFile.exists()) {
            throw new java.io.FileNotFoundException("파일을 찾을 수 없습니다: " + targetFile.getAbsolutePath());
        }

        // 2. Tika Text Extraction
        String content = extractText(targetFile);
        if (content == null || content.trim().isEmpty()) {
            log.warn("텍스트 추출 결과 없음: {}", file.getFileId());
            return;
        }

        // 3. Chunking
        List<String> chunks = chunkText(content, 1000, 200); // 1000자 청크, 200자 오버랩

        // 4. Embedding (Batch)
        List<List<Double>> embeddings = embeddingClient.embedBatch(chunks);

        // 5. Build Document
        Map<String, Object> doc = new HashMap<>();
        doc.put("FILE_ID", file.getFileId());
        doc.put("FILE_NM", file.getFileNm());
        doc.put("SAVED_FILE_PATH", file.getSavedFilePath());
        doc.put("FILE_UUID", file.getFileUuid());
        doc.put("URL", file.getUrl());
        doc.put("CONTENT_TYPE", file.getContentType());
        doc.put("REG_DT", file.getRegDt());

        List<Map<String, Object>> paragraphs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> p = new HashMap<>();
            p.put("content", chunks.get(i));
            if (i < embeddings.size()) {
                p.put("embedding", embeddings.get(i));
            }
            paragraphs.add(p);
        }
        doc.put("paragraphs", paragraphs);

        // 6. Index
        openSearchService.indexDocument("file", doc);
    }

    private String extractText(File file) throws Exception {
        BodyContentHandler handler = new BodyContentHandler(-1); // Unlimited size
        AutoDetectParser parser = new AutoDetectParser();
        Metadata metadata = new Metadata();
        try (InputStream stream = new FileInputStream(file)) {
            parser.parse(stream, handler, metadata);
            return handler.toString();
        }
    }

    private List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        int len = text.length();
        int start = 0;

        while (start < len) {
            int end = Math.min(start + chunkSize, len);
            chunks.add(text.substring(start, end));
            
            if (end == len) break;
            
            start += (chunkSize - overlap);
        }

        return chunks;
    }

    public com.cp.oslo.dto.SearchResultDto search(String query, Double textWeight, Integer size) {
        List<Double> vector = embeddingClient.embed(query);
        return openSearchService.searchNestedHybrid("file", "paragraphs", "content", "embedding", query, vector, textWeight, size);
    }

    public com.cp.oslo.dto.SearchResultDto searchText(String query, Integer size) {
        return openSearchService.searchNestedText("file", "paragraphs", "content", query, size);
    }

    public boolean deleteIndex() {
        return openSearchService.deleteIndex("file");
    }
}
