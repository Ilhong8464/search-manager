package com.cp.oslo.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
public class SearchResultDto {
    private long totalHits;
    private List<Map<String, Object>> documents;
    private Map<String, Map<String, List<String>>> highlights; // 문서 ID -> 필드명 -> 하이라이팅된 스니펫 목록
}
