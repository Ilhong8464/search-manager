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
@ConfigurationProperties(prefix = "search-intelligence")
@Data
public class VectorFieldConfig {
    
    private Integer dimension = 1024; // 전역 차원 설정 (기본값 1024)
    private Map<String, VectorField> vectorFields = new HashMap<>();

    @Data
    public static class VectorField {
        private String sourceField;    // 임베딩을 생성할 원본 필드
        private String targetField;    // 벡터가 저장될 필드
        private Integer dimension;     // 벡터 차원 (null이면 전역 설정 따름)
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
        VectorField field = vectorFields.get(indexName);
        if (field != null && field.getDimension() == null) {
            field.setDimension(this.dimension);
        }
        return field;
    }
}
