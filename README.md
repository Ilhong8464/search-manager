# Search Manager

MariaDB 데이터를 OpenSearch로 인덱싱하고 검색하는 Spring Boot 애플리케이션입니다.

## 주요 기능

- ✅ MariaDB 테이블을 OpenSearch 인덱스로 자동 동기화
- ✅ 여러 테이블/인덱스 관리 지원
- ✅ 동적 필드 매핑 설정
- ✅ Bulk 인덱싱으로 대용량 데이터 처리
- ✅ 동기화 이력 관리
- ✅ REST API 제공

## 기술 스택

- Java 17
- Spring Boot 3.2.0
- Spring Data JPA
- MariaDB
- OpenSearch 2.11.0
- Gradle

## 시작하기

### 1. 사전 요구사항

- JDK 17 이상
- MariaDB 10.x 이상
- OpenSearch 2.x 실행 중 (http://localhost:9200)

### 2. 데이터베이스 설정

`src/main/resources/application.yml` 파일에서 MariaDB 연결 정보를 설정합니다:

```yaml
spring:
  datasource:
    url: jdbc:mariadb://localhost:3306/your_database
    username: your_username
    password: your_password
```

### 3. OpenSearch 설정

`application.yml` 파일에서 OpenSearch 연결 정보를 설정합니다:

```yaml
opensearch:
  host: localhost
  port: 9200
  scheme: http
  username: admin
  password: admin
```

### 4. 애플리케이션 실행

```bash
# Gradle로 실행
./gradlew bootRun

# 또는 JAR 빌드 후 실행
./gradlew build
java -jar build/libs/search-manager-1.0.0.jar
```

애플리케이션은 기본적으로 `http://localhost:8080`에서 실행됩니다.

## 빠른 시작: uvw_manual 인덱싱

가장 간단한 방법으로 uvw_manual 뷰를 OpenSearch에 인덱싱하려면:

```bash
# 1. 애플리케이션 실행
./gradlew bootRun

# 2. 인덱스 생성 (인덱스 생성 + 데이터 동기화 자동 처리)
curl -X POST http://localhost:8080/api/v1/manual/create-index

# 3. 검색 테스트
curl "http://localhost:8080/api/v1/search/manual?query=시의원"
```

자세한 가이드는 [examples/MANUAL_INDEX_README.md](examples/MANUAL_INDEX_README.md)를 참고하세요.

## API 사용법

### 1. 인덱스 설정 생성

```bash
curl -X POST http://localhost:8080/api/v1/indexes \
  -H "Content-Type: application/json" \
  -d '{
    "indexName": "products",
    "description": "상품 검색 인덱스",
    "sourceTableName": "products",
    "numberOfShards": 1,
    "numberOfReplicas": 1,
    "enabled": true,
    "autoSync": false,
    "fieldMappings": [
      {
        "sourceColumnName": "id",
        "fieldType": "LONG",
        "indexed": true,
        "sortOrder": 0
      },
      {
        "sourceColumnName": "name",
        "targetFieldName": "product_name",
        "fieldType": "TEXT",
        "analyzer": "standard",
        "indexed": true,
        "sortOrder": 1
      },
      {
        "sourceColumnName": "description",
        "fieldType": "TEXT",
        "analyzer": "standard",
        "indexed": true,
        "sortOrder": 2
      },
      {
        "sourceColumnName": "price",
        "fieldType": "DOUBLE",
        "indexed": true,
        "sortOrder": 3
      },
      {
        "sourceColumnName": "created_at",
        "fieldType": "DATETIME",
        "indexed": true,
        "sortOrder": 4
      }
    ]
  }'
```

### 2. 모든 인덱스 설정 조회

```bash
curl http://localhost:8080/api/v1/indexes
```

### 3. 특정 인덱스 설정 조회

```bash
curl http://localhost:8080/api/v1/indexes/1
```

### 4. 인덱스 동기화 실행

```bash
# 특정 인덱스 동기화
curl -X POST http://localhost:8080/api/v1/indexes/1/sync

# 모든 활성화된 인덱스 동기화
curl -X POST http://localhost:8080/api/v1/indexes/sync-all
```

### 5. 동기화 이력 조회

```bash
curl http://localhost:8080/api/v1/indexes/1/history
```

### 6. 검색 실행

```bash
# 전체 검색
curl "http://localhost:8080/api/v1/search/products?query=*"

# 특정 키워드 검색
curl "http://localhost:8080/api/v1/search/products?query=노트북"
```

### 7. 인덱스 삭제

```bash
curl -X DELETE http://localhost:8080/api/v1/indexes/1
```

## 한국어 검색 설정

한국어 형태소 분석을 위해서는 OpenSearch의 `analysis-nori` 플러그인을 사용합니다.

### OpenSearch Nori 플러그인 설치

```bash
# OpenSearch 디렉토리에서 실행
bin/opensearch-plugin install analysis-nori
```

### 한국어 인덱스 설정 예제

```json
{
  "indexName": "korean_products",
  "sourceTableName": "products",
  "fieldMappings": [
    {
      "sourceColumnName": "name",
      "fieldType": "TEXT",
      "analyzer": "nori"
    },
    {
      "sourceColumnName": "description",
      "fieldType": "TEXT",
      "analyzer": "nori"
    }
  ]
}
```

## 데이터베이스 스키마 예제

```sql
CREATE TABLE products (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price DECIMAL(10, 2),
    stock INT,
    category VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 샘플 데이터
INSERT INTO products (name, description, price, stock, category) VALUES
('노트북', '고성능 게이밍 노트북', 1500000.00, 10, '전자제품'),
('무선 마우스', '인체공학적 디자인', 35000.00, 50, '액세서리'),
('키보드', '기계식 키보드', 120000.00, 30, '액세서리');
```

## 필드 타입

지원하는 OpenSearch 필드 타입:

- `TEXT` - 전문 검색용 텍스트
- `KEYWORD` - 정확한 매칭용 키워드
- `INTEGER` - 정수
- `LONG` - 긴 정수
- `DOUBLE` - 부동소수점
- `FLOAT` - 부동소수점
- `BOOLEAN` - 불린
- `DATE` - 날짜
- `DATETIME` - 날짜+시간
- `OBJECT` - 중첩 객체
- `GEO_POINT` - 지리적 좌표

## 프로젝트 구조

```
search-manager/
├── src/main/java/com/search/manager/
│   ├── config/          # 설정 클래스
│   ├── controller/      # REST API 컨트롤러
│   ├── domain/          # 엔티티 클래스
│   ├── dto/             # DTO 클래스
│   ├── repository/      # JPA 리포지토리
│   ├── service/         # 비즈니스 로직
│   └── SearchManagerApplication.java
├── src/main/resources/
│   └── application.yml
└── build.gradle
```

## 문제 해결

### OpenSearch 연결 실패

```
Error: Connection refused
```

OpenSearch가 실행 중인지 확인하세요:
```bash
curl http://localhost:9200
```

### 데이터베이스 연결 실패

`application.yml`의 데이터베이스 연결 정보를 확인하세요.

### 인덱싱 실패

- 테이블 이름과 컬럼 이름이 정확한지 확인
- 필드 매핑 타입이 데이터베이스 컬럼 타입과 호환되는지 확인

## 라이선스

MIT License
