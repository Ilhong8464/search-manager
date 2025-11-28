package com.cp.oslo.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 검색 엔진의 동의어(synonyms)와 불용어(stopwords) 설정 파일을 로드하는 유틸리티 클래스
 */
@Component
@Slf4j
public class AnalyzerConfigLoader {
    
    private static final String SYNONYMS_FILE = "search/synonyms.txt";
    private static final String STOPWORDS_FILE = "search/stopwords.txt";
    
    /**
     * 동의어 목록을 로드합니다.
     * 
     * @return 동의어 목록 (각 줄이 하나의 동의어 규칙)
     */
    public List<String> loadSynonyms() {
        return loadFile(SYNONYMS_FILE, "동의어");
    }
    
    /**
     * 불용어 목록을 로드합니다.
     * 
     * @return 불용어 목록
     */
    public List<String> loadStopwords() {
        return loadFile(STOPWORDS_FILE, "불용어");
    }
    
    /**
     * 파일을 읽어서 목록으로 반환합니다.
     * 주석(#으로 시작하는 줄)과 빈 줄은 무시합니다.
     * 
     * @param filePath 파일 경로 (resources 기준 상대 경로)
     * @param fileDescription 파일 설명 (로깅용)
     * @return 파일 내용 목록
     */
    private List<String> loadFile(String filePath, String fileDescription) {
        List<String> lines = new ArrayList<>();
        
        try {
            ClassPathResource resource = new ClassPathResource(filePath);
            
            if (!resource.exists()) {
                log.warn("{} 파일이 존재하지 않습니다: {}", fileDescription, filePath);
                return lines;
            }
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                
                String line;
                int lineNumber = 0;
                
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    
                    // 공백 제거
                    line = line.trim();
                    
                    // 빈 줄이나 주석은 무시
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    
                    lines.add(line);
                }
                
                log.info("{} 파일 로드 완료: {} ({}개 항목)", fileDescription, filePath, lines.size());
                
            }
            
        } catch (IOException e) {
            log.error("{} 파일 로드 실패: {}", fileDescription, filePath, e);
            throw new RuntimeException(fileDescription + " 파일 로드 실패: " + filePath, e);
        }
        
        return lines;
    }
}
