package com.cp.oslo.config;

import lombok.Getter;
import lombok.Setter;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * OpenSearch 클라이언트 설정
 */
@Configuration
@ConfigurationProperties(prefix = "opensearch")
@Getter
@Setter
public class OpenSearchConfig {

    private String host = "localhost";
    private int port = 9200;
    private String scheme = "http";
    private String username;
    private String password;
    private int connectTimeout = 5000;
    private int socketTimeout = 60000;

    @Bean
    public OpenSearchClient openSearchClient() {
        // REST 클라이언트 빌더 생성
        RestClientBuilder builder = RestClient.builder(
                new HttpHost(host, port, scheme)
        );

        // 인증 설정
        if (username != null && password != null) {
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password)
            );

            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
            );
        }

        // 타임아웃 설정
        builder.setRequestConfigCallback(requestConfigBuilder ->
                requestConfigBuilder
                        .setConnectTimeout(connectTimeout)
                        .setSocketTimeout(socketTimeout)
        );

        RestClient restClient = builder.build();

        // Transport 생성
        // JacksonJsonpMapper에 JavaTimeModule 등록
        JacksonJsonpMapper jacksonJsonpMapper = new JacksonJsonpMapper(
            new ObjectMapper().registerModule(new JavaTimeModule())
        );
        RestClientTransport transport = new RestClientTransport(
                restClient,
                jacksonJsonpMapper
        );

        return new OpenSearchClient(transport);
    }
}
