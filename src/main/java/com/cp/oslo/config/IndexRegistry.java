package com.cp.oslo.config;

import com.cp.oslo.model.FieldDefinition;
import com.cp.oslo.model.IndexDefinition;
import com.cp.oslo.model.FieldDefinition.FieldType;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 인덱스 정의 저장소
 * 코드 레벨에서 인덱스 구조(필드 매핑 등)를 관리합니다.
 */
@Component
public class IndexRegistry {

    @Getter
    private final Map<String, IndexDefinition> definitions = new HashMap<>();

    @PostConstruct
    public void init() {
        // 1. Manual 인덱스 정의
        definitions.put("manual", IndexDefinition.builder()
                .indexName("manual")
                .sourceTableName("uvw_manual")
                .description("매뉴얼")
                .idColumn("MANUAL_UUID")
                .fields(List.of(
                        field("MANUAL_UUID", FieldType.KEYWORD),
                        field("TITLE", FieldType.TEXT, "nori"),
                        field("CONTENTS", FieldType.TEXT, "nori"),
                        field("CAT_NM", FieldType.KEYWORD),
                        field("REG_DT", FieldType.DATE)
                        // 필요한 필드들 추가
                ))
                .build());

        // 2. Manual QnA 인덱스 정의
        definitions.put("manual-qna", IndexDefinition.builder()
                .indexName("manual-qna")
                .sourceTableName("uvw_manual_qna")
                .description("QnA")
                .idColumn("MANUAL_UUID")
                .fields(List.of(
                        field("MANUAL_UUID", FieldType.KEYWORD),
                        field("TITLE", FieldType.TEXT, "nori"),
                        field("CONTENTS", FieldType.TEXT, "nori")
                ))
                .build());

        // 3. Call 인덱스 정의
        definitions.put("call", IndexDefinition.builder()
                .indexName("call")
                .sourceTableName("uvw_call")
                .description("상담이력")
                .idColumn("CALL_UUID")
                .fields(List.of(
                        field("CALL_UUID", FieldType.KEYWORD),
                        field("FULL_CALL_CAT_NM", FieldType.TEXT, "nori"),
                        field("QUESTION", FieldType.TEXT, "nori"),
                        field("ANSWER", FieldType.TEXT, "nori"),
//                        field("MEMO", FieldType.TEXT, "nori"),
                        field("FULL_DEPT_NM", FieldType.TEXT, "nori"),
                        field("CALL_TYPE_NM", FieldType.TEXT, "nori"),
                        field("RGTR_NM", FieldType.TEXT, "nori"),
                        field("CALL_DT", FieldType.DATE)
                ))
                .build());

        // 4. Notice 인덱스 정의
        definitions.put("notice", IndexDefinition.builder()
                .indexName("notice")
                .sourceTableName("uvw_doc_notice") // DB 뷰 이름은 uvw_doc_notice 유지
                .description("공지사항")
                .idColumn("DOC_UUID") // DB 컬럼명 DOC_UUID 유지
                .fields(List.of(
                        field("DOC_UUID", FieldType.KEYWORD), // DB 컬럼명 DOC_UUID 유지
                        field("DOC_NM", FieldType.TEXT, "nori"), // DB 컬럼명 DOC_NM 유지
                        field("CONTENTS", FieldType.TEXT, "nori"),
                        field("REG_DT", FieldType.DATE)
                ))
                .build());

        // 5. Integrated Search 인덱스 정의 (UVW_SEARCH)
        definitions.put("unified", IndexDefinition.builder()
                .indexName("unified")
                .sourceTableName("UVW_SEARCH")
                .description("통합 검색")
                .idColumn("UUID")
                .fields(List.of(
                        field("DATA_TYPE", FieldType.KEYWORD),
                        field("UUID", FieldType.KEYWORD),
                        field("TITLE", FieldType.TEXT, "nori"),
                        field("CONTENTS", FieldType.TEXT, "nori"),
                        field("EXTENTION", FieldType.KEYWORD)
                ))
                .build());

        // 6. File 인덱스 정의
        definitions.put("file", IndexDefinition.builder()
                .indexName("file")
                .sourceTableName("TB_FILE")
                .description("첨부파일")
                .idColumn("FILE_ID")
                .fields(List.of(
                        field("FILE_ID", FieldType.KEYWORD),
                        field("FILE_NM", FieldType.TEXT, "nori"),
                        field("SAVED_FILE_PATH", FieldType.KEYWORD),
                        field("FILE_UUID", FieldType.KEYWORD),
                        field("URL", FieldType.KEYWORD),
                        field("CONTENT_TYPE", FieldType.KEYWORD),
                        field("REG_DT", FieldType.DATE),
                        FieldDefinition.builder()
                                .targetField("paragraphs")
                                .type(FieldType.NESTED)
                                .indexed(true)
                                .subFields(List.of(
                                        field("content", FieldType.TEXT, "nori"),
                                        FieldDefinition.builder()
                                                .targetField("embedding")
                                                .type(FieldType.KNN_VECTOR)
                                                .dimension(768)
                                                .indexed(true)
                                                .build()
                                ))
                                .build()
                ))
                .build());
    }

    public IndexDefinition get(String indexName) {
        return definitions.get(indexName);
    }

    // 헬퍼 메서드
    private FieldDefinition field(String source, FieldType type) {
        return FieldDefinition.builder().sourceColumn(source).type(type).indexed(true).build();
    }

    private FieldDefinition field(String source, FieldType type, String analyzer) {
        return FieldDefinition.builder().sourceColumn(source).type(type).indexed(true).analyzer(analyzer).build();
    }
}
