package com.cp.oslo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 벡터 필드 설정
 */
@Configuration
@ConfigurationProperties(prefix = "embedding")
@Data
public class VectorFieldConfig {
    
    private Map<String, VectorField> vectorFields = new HashMap<>();

    @Data
    public static class VectorField {
        private String sourceField;    // 임베딩을 생성할 원본 필드
        private String targetField;    // 벡터가 저장될 필드
        private Integer dimension = 768;
        private String engine = "lucene";
        private String spaceType = "cosinesimil";
    }
    
    /**
     * 인덱스에 벡터 필드가 설정되어 있는지 확인
     */
    public boolean hasVectorField(String indexName) {
        return vectorFields.containsKey(indexName);
    }
    
    /**
     * 인덱스의 벡터 필드 설정 가져오기
     */
    public VectorField getVectorField(String indexName) {
        return vectorFields.get(indexName);
    }
}
