#!/bin/bash

echo "======================================"
echo "uvw_manual 인덱스 생성 스크립트"
echo "======================================"
echo ""

# 1. 애플리케이션이 실행 중인지 확인
echo "1. Spring Boot 애플리케이션 상태 확인 중..."
if ! curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
    echo "❌ 애플리케이션이 실행 중이지 않습니다."
    echo "   먼저 애플리케이션을 실행하세요: ./gradlew bootRun"
    exit 1
fi

echo "✅ 애플리케이션이 실행 중입니다."
echo ""

# 2. 인덱스 정보 확인
echo "2. 기존 인덱스 확인 중..."
INFO_RESPONSE=$(curl -s http://localhost:8080/api/v1/manual/info)
EXISTS=$(echo "$INFO_RESPONSE" | grep -o '"exists":[^,]*' | grep -o 'true\|false')

if [ "$EXISTS" = "true" ]; then
    echo "⚠️  manual 인덱스가 이미 존재합니다."
    echo "$INFO_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$INFO_RESPONSE"
    echo ""
    echo "기존 인덱스를 재동기화하시겠습니까? (y/n): "
    read -r answer

    if [ "$answer" = "y" ] || [ "$answer" = "Y" ]; then
        echo ""
        echo "3. 데이터 재동기화 중... (시간이 걸릴 수 있습니다)"
        SYNC_RESPONSE=$(curl -s -X POST http://localhost:8080/api/v1/manual/sync)
        echo "$SYNC_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$SYNC_RESPONSE"
    else
        echo "작업을 취소합니다."
        exit 0
    fi
else
    echo "✅ 기존 인덱스가 없습니다. 새로 생성합니다."
    echo ""

    # 3. 인덱스 생성 및 동기화
    echo "3. 인덱스 생성 및 데이터 동기화 중... (시간이 걸릴 수 있습니다)"
    CREATE_RESPONSE=$(curl -s -X POST http://localhost:8080/api/v1/manual/create-index)
    echo "$CREATE_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$CREATE_RESPONSE"
fi

echo ""
echo "======================================"
echo "4. 검색 테스트"
echo "======================================"
echo ""
echo "다음 명령어로 검색할 수 있습니다:"
echo ""
echo "  curl \"http://localhost:8080/api/v1/search/manual?query=시의원\""
echo "  curl \"http://localhost:8080/api/v1/search/manual?query=출판사\""
echo ""
echo "또는 브라우저에서:"
echo "  http://localhost:8080/api/v1/search/manual?query=시의원"
echo ""
echo "======================================"
echo "작업 완료"
echo "======================================"
