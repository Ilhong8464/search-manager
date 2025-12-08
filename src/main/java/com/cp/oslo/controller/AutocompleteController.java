package com.cp.oslo.controller;

import com.cp.oslo.dto.SearchResultDto;
import com.cp.oslo.service.AutocompleteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/autocomplete")
@RequiredArgsConstructor
public class AutocompleteController {

    private final AutocompleteService autocompleteService;

    /**
     * 자동완성 인덱스 초기화 (관리자용)
     */
    @PostMapping("/init")
    public ResponseEntity<?> initIndex() {
        autocompleteService.initIndex();
        return ResponseEntity.ok("자동완성 인덱스가 초기화되었습니다.");
    }

    /**
     * 테스트 데이터 적재 (테스트용)
     */
    @PostMapping("/ingest/test")
    public ResponseEntity<?> ingestTestData() {
        // 예시 데이터
        String[] keywords = {
            "강남 맛집", "강남역 카페", "강아지 사료", "강원도 여행",
            "김치찌개", "김밥천국", "기초생활수급자", "기초연금",
            "나이키 신발", "나비", "노트북 파우치",
            "다람쥐", "다이소 영업시간",
            "라면 맛있게 끓이는 법", "라디오 스타"
        };

        for (String keyword : keywords) {
            autocompleteService.indexKeyword(keyword, 1, "keyword");
        }
        return ResponseEntity.ok("테스트 데이터 " + keywords.length + "건이 적재되었습니다.");
    }
    
    /**
     * DB 데이터 적재 (실제 데이터용) - 기존 인덱스를 초기화하고 재적재합니다.
     */
    @PostMapping("/ingest/db")
    public ResponseEntity<?> ingestDbData() {
        int count = autocompleteService.rebuildIndex();
        if (count == -1) {
            return ResponseEntity.internalServerError().body("자동완성 인덱스 재구축 실패");
        }
        return ResponseEntity.ok("자동완성 인덱스를 재구축하고 DB에서 " + count + "건의 명사 데이터를 추출하여 적재했습니다.");
    }

    /**
     * 자동완성 검색
     * @param query 검색어 (예: "강ㄴ")
     */
    @GetMapping("/suggest")
    public ResponseEntity<SearchResultDto> suggest(@RequestParam String query) {
        SearchResultDto result = autocompleteService.suggest(query);
        return ResponseEntity.ok(result);
    }
}