package com.cp.oslo.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * 인덱스 구조 정의 (Immutable Configuration)
 */
@Getter
@Builder
@ToString
public class IndexDefinition {
    private final String indexName;
    private final String sourceTableName;
    private final String description;
    
    // 식별자 컬럼 (UUID 등) - 명시적 지정
    private final String idColumn;

    private final List<FieldDefinition> fields;
}
