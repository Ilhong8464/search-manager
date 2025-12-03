package com.cp.oslo.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * 임베딩 서비스와 통신하는 클라이언트
 */
@Component
@Slf4j
public class EmbeddingClient {

    private final WebClient webClient;

    public EmbeddingClient(@Value("${embedding.service.url:http://localhost:8000}") String embeddingServiceUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(embeddingServiceUrl)
                .exchangeStrategies(org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)) // 16MB
                        .build())
                .build();
        log.info("EmbeddingClient 초기화: {}", embeddingServiceUrl);
    }

    /**
     * 단일 텍스트의 임베딩 벡터 생성
     */
    public List<Double> embed(String text) {
        try {
            Map<String, String> request = Map.of("text", text);

            log.debug("임베딩 요청: {}", text);

            Map<String, Object> response = webClient.post()
                    .uri("/embed")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (response == null) {
                throw new RuntimeException("임베딩 서비스 응답이 null입니다");
            }

            // JSON 파싱 시 숫자는 Double로 변환됨
            List<?> embeddingList = (List<?>) response.get("embedding");
            if (embeddingList == null) {
                throw new RuntimeException("임베딩 응답에 'embedding' 필드가 없습니다");
            }

            List<Double> embedding = embeddingList.stream()
                    .map(obj -> {
                        if (obj instanceof Double) {
                            return (Double) obj;
                        } else if (obj instanceof Float) {
                            return ((Float) obj).doubleValue();
                        } else if (obj instanceof Number) {
                            return ((Number) obj).doubleValue();
                        }
                        throw new IllegalArgumentException("Invalid embedding type: " + obj.getClass());
                    })
                    .toList();

            log.debug("임베딩 생성 완료: dimension={}", embedding.size());
            return embedding;

        } catch (Exception e) {
            log.error("임베딩 생성 실패: {}", text, e);
            throw new RuntimeException("임베딩 생성 실패", e);
        }
    }

    /**
     * 배치 텍스트의 임베딩 벡터 생성
     */
    public List<List<Double>> embedBatch(List<String> texts) {
        try {
            Map<String, List<String>> request = Map.of("texts", texts);

            log.debug("배치 임베딩 요청: {} 개 문서", texts.size());

            Map<String, Object> response = webClient.post()
                    .uri("/embed/batch")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (response == null) {
                throw new RuntimeException("임베딩 서비스 응답이 null입니다");
            }

            List<?> embeddingsList = (List<?>) response.get("embeddings");
            if (embeddingsList == null) {
                throw new RuntimeException("임베딩 응답에 'embeddings' 필드가 없습니다");
            }

            List<List<Double>> embeddings = embeddingsList.stream()
                    .map(obj -> {
                        List<?> embeddingList = (List<?>) obj;
                        return embeddingList.stream()
                                .map(num -> {
                                    if (num instanceof Double) {
                                        return (Double) num;
                                    } else if (num instanceof Number) {
                                        return ((Number) num).doubleValue();
                                    }
                                    throw new IllegalArgumentException("Invalid embedding type: " + num.getClass());
                                })
                                .toList();
                    })
                    .toList();

            log.debug("배치 임베딩 생성 완료: {} 개 문서", embeddings.size());
            return embeddings;

        } catch (Exception e) {
            log.error("배치 임베딩 생성 실패", e);
            throw new RuntimeException("배치 임베딩 생성 실패", e);
        }
    }

    /**
     * 임베딩 서비스 연결 확인
     */
    public boolean isAvailable() {
        try {
            webClient.get()
                    .uri("/docs")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("임베딩 서비스 연결 성공");
            return true;

        } catch (Exception e) {
            log.warn("임베딩 서비스 연결 실패: {}", e.getMessage());
            return false;
        }
    }
}
