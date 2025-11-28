# uvw_manual 뷰 인덱싱 가이드

이 가이드는 MariaDB의 `uvw_manual` 뷰를 OpenSearch에 인덱싱하는 방법을 설명합니다.

## 📋 uvw_manual 뷰 정보

- **총 컬럼 수**: 51개
- **주요 필드**:
  - 매뉴얼 정보: TITLE, CONTENTS, HTML, DESCRIPTION
  - 카테고리: CAT_NM, FULL_CAT_NM, CAT_NM_1~3
  - 부서/담당자: FULL_DEPT_NM, CHARGE_NM
  - 통계: READ_CNT, CONFIRM_CNT, TOT_READ_CNT 등
  - 상태: USE_YN, DEL_YN
  - 일시: REG_DT, MDF_DT

## 🚀 인덱스 생성 방법

### 방법 1: REST API 한 번 호출 (가장 간단! 권장)

1. **애플리케이션 실행**
   ```bash
   ./gradlew bootRun
   ```

2. **인덱스 생성 API 호출 (인덱스 생성 + 데이터 동기화 자동 처리)**
   ```bash
   curl -X POST http://localhost:8080/api/v1/manual/create-index
   ```

   응답 예시:
   ```json
   {
     "success": true,
     "message": "매뉴얼 인덱스가 성공적으로 생성되었습니다.",
     "indexId": 1,
     "indexName": "manual",
     "sourceTable": "uvw_manual",
     "fieldMappingsCount": 32,
     "searchUrl": "/api/v1/search/manual?query=시의원"
   }
   ```

### 방법 2: 쉘 스크립트 사용

```bash
# 애플리케이션 실행
./gradlew bootRun

# 다른 터미널에서 스크립트 실행
./create-manual-index.sh
```

이 스크립트는 자동으로:
- 애플리케이션 상태 확인
- 기존 인덱스 존재 여부 확인
- 인덱스 생성 또는 재동기화
- 검색 테스트 명령어 안내

### 방법 3: 범용 REST API 사용 (수동 제어)

1. **애플리케이션 실행**
   ```bash
   ./gradlew bootRun
   ```

2. **인덱스 설정 생성**
   ```bash
   curl -X POST http://localhost:8080/api/v1/indexes \
     -H "Content-Type: application/json" \
     -d @examples/manual-index-config.json
   ```

3. **응답에서 인덱스 ID 확인** (예: "id": 1)

4. **데이터 동기화**
   ```bash
   curl -X POST http://localhost:8080/api/v1/indexes/1/sync
   ```

## 🔍 검색 테스트

인덱스 생성 후 다음 명령어로 검색을 테스트할 수 있습니다:

### 전체 검색
```bash
curl "http://localhost:8080/api/v1/search/manual?query=*"
```

### 키워드 검색
```bash
# "시의원" 키워드 검색
curl "http://localhost:8080/api/v1/search/manual?query=시의원"

# "출판사" 키워드 검색
curl "http://localhost:8080/api/v1/search/manual?query=출판사"

# "인허가" 키워드 검색
curl "http://localhost:8080/api/v1/search/manual?query=인허가"
```

### 브라우저에서 테스트
```
http://localhost:8080/api/v1/search/manual?query=시의원
```

## 📊 인덱스 구조

### 주요 검색 필드 (TEXT + nori 분석기)
- `title`: 매뉴얼 제목
- `contents`: 매뉴얼 내용 (텍스트)
- `html`: 매뉴얼 HTML
- `description`: 설명
- `cat_nm`: 카테고리명
- `full_cat_nm`: 전체 카테고리 경로
- `full_dept_nm`: 부서명
- `charge_nm`: 담당자명

### 필터용 필드 (KEYWORD)
- `cat_nm_1`, `cat_nm_2`, `cat_nm_3`: 카테고리 계층
- `importance`: 중요도 코드
- `importance_nm`: 중요도 명
- `USE_YN`: 사용 여부
- `DEL_YN`: 삭제 여부

### 통계 필드 (INTEGER)
- `READ_CNT`: 읽기 수
- `CONFIRM_CNT`: 확인 수
- `TOT_READ_CNT`: 총 읽기 수
- `TOT_CONFIRM_CNT`: 총 확인 수
- `MANUAL_QNA_CNT`: Q&A 수

### 날짜 필드 (DATETIME)
- `reg_dt`: 등록일시
- `mdf_dt`: 수정일시

## 🔧 설정 변경

`examples/manual-index-config.json` 파일을 수정하여 인덱스 설정을 변경할 수 있습니다:

- **샤드/레플리카 조정**
  ```json
  "numberOfShards": 1,
  "numberOfReplicas": 1
  ```

- **필드 추가/제거**
  ```json
  {
    "sourceColumnName": "컬럼명",
    "targetFieldName": "필드명",
    "fieldType": "TEXT|KEYWORD|INTEGER|DATETIME",
    "analyzer": "nori",
    "indexed": true,
    "sortOrder": 순서
  }
  ```

- **자동 동기화 설정**
  ```json
  "autoSync": true,
  "syncIntervalMinutes": 60
  ```

## 🛠️ 문제 해결

### 인덱스 생성 실패
- OpenSearch가 실행 중인지 확인: `curl http://localhost:9200`
- application.yml의 OpenSearch 연결 정보 확인

### 동기화 실패
- MariaDB 연결 정보 확인
- `uvw_manual` 뷰가 존재하는지 확인
- 뷰에 접근 권한이 있는지 확인

### 검색 결과가 없음
- 데이터 동기화가 완료되었는지 확인
- OpenSearch에 데이터가 인덱싱되었는지 확인:
  ```bash
  curl "http://localhost:9200/manual/_count"
  ```

### 한국어 검색이 작동하지 않음
- OpenSearch에 nori 플러그인이 설치되어 있는지 확인:
  ```bash
  bin/opensearch-plugin list
  ```
- 플러그인이 없다면 설치:
  ```bash
  bin/opensearch-plugin install analysis-nori
  # OpenSearch 재시작 필요
  ```

## 📝 추가 API

### manual 인덱스 정보 조회
```bash
curl http://localhost:8080/api/v1/manual/info
```

응답 예시:
```json
{
  "exists": true,
  "indexId": 1,
  "indexName": "manual",
  "sourceTable": "uvw_manual",
  "enabled": true,
  "fieldMappingsCount": 32,
  "lastSyncTime": "2024-01-15T10:30:00",
  "syncUrl": "/api/v1/manual/sync",
  "searchUrl": "/api/v1/search/manual?query=키워드"
}
```

### manual 인덱스 재동기화
```bash
curl -X POST http://localhost:8080/api/v1/manual/sync
```

### 기존 인덱스 삭제 (범용 API)
```bash
curl -X DELETE http://localhost:8080/api/v1/indexes/1
```

### 동기화 이력 조회
```bash
curl http://localhost:8080/api/v1/indexes/1/history
```

### 모든 인덱스 조회
```bash
curl http://localhost:8080/api/v1/indexes
```

## 💡 팁

1. **대용량 데이터**: uvw_manual에 데이터가 많다면 동기화에 시간이 걸릴 수 있습니다. 배치 크기는 1000건으로 설정되어 있습니다.

2. **부분 검색**: OpenSearch의 강력한 전문 검색 기능을 활용하여 제목, 내용, 카테고리 등에서 자동으로 검색됩니다.

3. **필터링**: KEYWORD 필드(카테고리, 부서, 상태 등)를 활용하여 필터링할 수 있습니다.

4. **성능 최적화**: 검색 빈도가 높다면 샤드 수를 늘리고, 고가용성이 필요하면 레플리카를 늘리세요.
