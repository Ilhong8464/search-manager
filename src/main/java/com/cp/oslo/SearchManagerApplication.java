package com.cp.oslo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Search Manager Application
 *
 * MariaDB 데이터를 OpenSearch로 인덱싱하고 검색하는 애플리케이션입니다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SearchManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchManagerApplication.class, args);
    }
}
