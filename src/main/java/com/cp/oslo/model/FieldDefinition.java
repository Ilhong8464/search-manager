package com.cp.oslo.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 필드 매핑 정의 (Immutable Configuration)
 */
@Getter
@Builder
@ToString
public class FieldDefinition {
    private final String sourceColumn;      // DB 컬럼명
    private final String targetField;       // OpenSearch 필드명 (null이면 sourceColumn 사용)
    private final FieldType type;           // 필드 타입
    private final boolean indexed;          // 인덱싱 여부
    
    // Text 분석기 설정
    private final String analyzer;

    // Vector 설정
    private final Integer dimension;

    public String getEffectiveFieldName() {
        return targetField != null ? targetField : sourceColumn;
    }

    public enum FieldType {
        TEXT, KEYWORD, INTEGER, LONG, DOUBLE, FLOAT, BOOLEAN, DATE, OBJECT, KNN_VECTOR
    }
}
