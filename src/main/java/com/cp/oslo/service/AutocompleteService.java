package com.cp.oslo.service;

import com.cp.oslo.dto.SearchResultDto;
import com.cp.oslo.util.HangulJamoUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch._types.analysis.TokenChar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutocompleteService {

    private final OpenSearchClient openSearchClient;
    private final RestClient restClient;
    private final HangulJamoUtils hangulJamoUtils;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private static final String INDEX_NAME = "suggest_index";

    /**
     * 자동완성 인덱스를 초기화합니다. (없으면 생성)
     */
    public void initIndex() {
        try {
            boolean exists = openSearchClient.indices().exists(ExistsRequest.of(e -> e.index(INDEX_NAME))).value();
            if (!exists) {
                createSuggestIndex();
            }
        } catch (IOException e) {
            log.error("자동완성 인덱스 초기화 실패", e);
            throw new RuntimeException("자동완성 인덱스 초기화 실패", e);
        }
    }

    /**
     * 자동완성 인덱스를 재구축합니다. (삭제 후 재생성 및 데이터 적재)
     * @return 적재된 데이터 건수
     */
    public int rebuildIndex() {
        log.info("자동완성 인덱스 재구축 시작...");
        int count = 0;
        try {
            boolean exists = openSearchClient.indices().exists(ExistsRequest.of(e -> e.index(INDEX_NAME))).value();
            if (exists) {
                openSearchClient.indices().delete(d -> d.index(INDEX_NAME));
                log.info("기존 자동완성 인덱스 삭제 완료");
            }
            createSuggestIndex();
            count = ingestFromDatabase();
            log.info("자동완성 인덱스 재구축 완료 ({}건 적재)", count);
        } catch (Exception e) {
            log.error("자동완성 인덱스 재구축 실패", e);
            return -1;
        }
        return count;
    }

    /**
     * 인덱스를 생성합니다.
     */
    private void createSuggestIndex() throws IOException {
        CreateIndexRequest request = CreateIndexRequest.of(c -> c
                .index(INDEX_NAME)
                .settings(s -> s
                        .analysis(a -> a
                                .tokenizer("edge_ngram_tokenizer", t -> t
                                        .definition(td -> td
                                                .edgeNgram(en -> en
                                                        .minGram(1)
                                                        .maxGram(20)
                                                        .tokenChars(List.of(
                                                                TokenChar.Letter,
                                                                TokenChar.Digit
                                                        ))
                                                )
                                        )
                                )
                                .analyzer("edge_ngram_analyzer", an -> an
                                        .custom(ca -> ca
                                                .tokenizer("edge_ngram_tokenizer")
                                                .filter("lowercase")
                                        )
                                )
                                .analyzer("keyword_analyzer", an -> an
                                        .custom(ca -> ca
                                                .tokenizer("keyword")
                                                .filter("lowercase")
                                        )
                                )
                        )
                )
                .mappings(m -> m
                        .properties("keyword", p -> p
                                .text(t -> t
                                        .analyzer("standard")
                                        .fields("raw", f -> f.keyword(k -> k))
                                )
                        )
                        .properties("keyword_jamo", p -> p
                                .text(t -> t
                                        .analyzer("edge_ngram_analyzer")
                                        .searchAnalyzer("keyword_analyzer")
                                )
                        )
                        .properties("keyword_chosung", p -> p
                                .text(t -> t
                                        .analyzer("edge_ngram_analyzer")
                                        .searchAnalyzer("keyword_analyzer")
                                )
                        )
                        .properties("weight", p -> p.integer(i -> i))
                        .properties("type", p -> p.keyword(k -> k))
                )
        );

        openSearchClient.indices().create(request);
        log.info("자동완성 인덱스({})" + " 생성 완료", INDEX_NAME);
    }

    /**
     * 키워드를 자동완성 인덱스에 추가합니다.
     */
    public void indexKeyword(String keyword, int weight, String type) {
        if (keyword == null || keyword.trim().isEmpty()) return;

        try {
            String cleanKeyword = keyword.replaceAll("[\\(\\[\\{].*?[\\)\\]\\}]", "").trim();
            if (cleanKeyword.isEmpty()) return;

            String jamo = hangulJamoUtils.decompose(cleanKeyword);
            String chosung = hangulJamoUtils.extractChosung(cleanKeyword);

            Map<String, Object> doc = new HashMap<>();
            doc.put("keyword", cleanKeyword);
            doc.put("keyword_jamo", jamo);
            doc.put("keyword_chosung", chosung);
            doc.put("weight", weight);
            doc.put("type", type);
            
            String docId = java.util.Base64.getEncoder().encodeToString(cleanKeyword.getBytes());

            openSearchClient.index(i -> i
                    .index(INDEX_NAME)
                    .id(docId)
                    .document(doc)
            );
            
            log.debug("자동완성 키워드 적재: {} (자소: {}, 초성: {})", cleanKeyword, jamo, chosung);

        } catch (IOException e) {
            log.error("키워드 인덱싱 실패: {}", keyword, e);
        }
    }

    /**
     * OpenSearch _analyze API를 호출하여 텍스트에서 명사만 추출합니다.
     */
    public List<String> analyzeTextForNouns(String text) {
        if (text == null || text.trim().isEmpty()) {
            return List.of();
        }
        try {
            Map<String, Object> body = new HashMap<>();
            
            Map<String, Object> tokenizer = new HashMap<>();
            tokenizer.put("type", "nori_tokenizer");
            tokenizer.put("decompound_mode", "none"); 
            body.put("tokenizer", tokenizer);
            
            Map<String, Object> filter = new HashMap<>();
            filter.put("type", "nori_part_of_speech");
            filter.put("stoptags", List.of(
                "EP", "EF", "EC", "ETN", "ETM",
                "IC",
                "JKS", "JKC", "JKG", "JKO", "JKB", "JKV", "JKQ", "JX", "JC",
                "MAG", "MAJ",
                "MM",
                "SP", "SSC", "SSO", "SC", "SE",
                "XPN", "XSA", "XSN", "XSV",
                "UNA", "NA", "VSV"
            ));
            
            body.put("filter", List.of(filter));
            body.put("text", text);

            Request request = new Request("POST", "/_analyze");
            request.setJsonEntity(objectMapper.writeValueAsString(body));

            Response response = restClient.performRequest(request);
            
            JsonNode rootNode = objectMapper.readTree(response.getEntity().getContent());
            JsonNode tokensNode = rootNode.path("tokens");
            
            List<String> nouns = new ArrayList<>();
            if (tokensNode.isArray()) {
                for (JsonNode token : tokensNode) {
                    nouns.add(token.path("token").asText());
                }
            }
            return nouns;

        } catch (Exception e) {
            log.error("OpenSearch Analyze API 호출 실패: {}", text, e);
            return List.of();
        }
    }

    /**
     * 제목에서 어절 단위로 키워드를 추출합니다.
     * Nori 분석기를 이용해 조사/어미를 제거하고 명사만 남겨 재조립합니다.
     * 예: "산림경영계획인가를" -> [산림경영계획, 인가] -> "산림경영계획인가"
     */
    private List<String> extractWordsFromTitle(String title) {
        List<String> words = new ArrayList<>();
        
        // 공백뿐만 아니라 특수문자(한글/영문/숫자 제외한 모든 문자)를 구분자로 취급하여 분리
        // 예: "단어A・단어B" -> ["단어A", "단어B"]
        String[] tokens = title.split("[^가-힣a-zA-Z0-9]+");
        
        for (String token : tokens) {
            // 이미 특수문자로 split 했으므로 token에는 특수문자가 없지만, 혹시 모를 잔여물 제거 및 안전장치
            String cleanToken = token.trim(); 
            if (cleanToken.length() < 2) continue;
            
            // 2. Nori 분석기를 통한 정제 (조사 제거 후 재조립)
            // analyzeTextForNouns는 이미 조사/어미 제거 필터가 적용되어 있음
            List<String> analyzedTokens = analyzeTextForNouns(cleanToken);
            
            if (!analyzedTokens.isEmpty()) {
                // 분석된 토큰들을 이어 붙여서 하나의 단어로 복원
                // 예: "목재생산업은" -> [목재, 생산업] -> "목재생산업"
                String refined = String.join("", analyzedTokens);
                
                if (refined.length() >= 2) {
                    words.add(refined);
                }
            }
        }
        return words;
    }

    /**
     * DB에서 데이터를 조회하여 명사를 추출하고 자동완성 인덱스에 적재합니다.
     */
    public int ingestFromDatabase() {
        String sql = "SELECT TITLE FROM uvw_search WHERE DATA_TYPE != 'CALL'";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        
        int ingestedCount = 0;
        for (Map<String, Object> row : rows) {
            String title = (String) row.get("TITLE");
            if (title != null && !title.trim().isEmpty()) {
                // 1. OpenSearch Analyze API를 통해 명사 추출
                List<String> nouns = analyzeTextForNouns(title);
                for (String noun : nouns) {
                    indexKeyword(noun, 1, "db_noun");
                    ingestedCount++;
                }
                
                // 2. 어절 단위 추출 (정규식 기반 조사 제거)
                List<String> words = extractWordsFromTitle(title);
                for (String word : words) {
                    indexKeyword(word, 1, "db_word");
                    ingestedCount++;
                }
            }
        }
        log.info("{}건의 DB TITLE에서 명사 및 어절을 추출하여 자동완성 인덱스에 적재했습니다.", ingestedCount);
        return ingestedCount;
    }

    /**
     * 자동완성 검색을 수행합니다.
     */
    public SearchResultDto suggest(String query) {
        if (query == null || query.trim().isEmpty()) {
            return SearchResultDto.builder().totalHits(0).documents(List.of()).build();
        }

        try {
            String cleanQuery = query.trim();
            String queryJamo = hangulJamoUtils.decompose(cleanQuery);
            String queryChosung = hangulJamoUtils.extractChosung(cleanQuery);
            
            boolean isChosungOnly = hangulJamoUtils.isAllChosung(cleanQuery);

            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .index(INDEX_NAME)
                    .size(10)
                    .query(q -> q
                            .bool(b -> {
                                b.should(sh -> sh.match(m -> m.field("keyword").query(org.opensearch.client.opensearch._types.FieldValue.of(cleanQuery)).boost(2.0f)));
                                
                                if (isChosungOnly) {
                                    b.should(sh -> sh.match(m -> m.field("keyword_chosung").query(org.opensearch.client.opensearch._types.FieldValue.of(queryChosung))));
                                } else {
                                    b.should(sh -> sh.match(m -> m.field("keyword_jamo").query(org.opensearch.client.opensearch._types.FieldValue.of(queryJamo))));
                                }
                                return b;
                            })
                    )
            );

            SearchResponse<Map> response = openSearchClient.search(searchRequest, Map.class);

            List<Map<String, Object>> documents = response.hits().hits().stream()
                    .map(hit -> {
                        Map<String, Object> source = new HashMap<>(hit.source());
                        return source;
                    })
                    .collect(Collectors.toList());

            return SearchResultDto.builder()
                    .totalHits(response.hits().total().value())
                    .documents(documents)
                    .build();

        } catch (IOException e) {
            log.error("자동완성 검색 실패", e);
            throw new RuntimeException("자동완성 검색 실패", e);
        }
    }
}