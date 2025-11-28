-- 샘플 데이터베이스 및 테이블 생성 스크립트

-- 데이터베이스 생성
CREATE DATABASE IF NOT EXISTS search_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE search_demo;

-- 상품 테이블
CREATE TABLE products (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL COMMENT '상품명',
    description TEXT COMMENT '상품 설명',
    price DECIMAL(10, 2) NOT NULL COMMENT '가격',
    stock INT NOT NULL DEFAULT 0 COMMENT '재고',
    category VARCHAR(100) COMMENT '카테고리',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    INDEX idx_category (category),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 테이블';

-- 샘플 데이터 삽입
INSERT INTO products (name, description, price, stock, category) VALUES
('삼성 갤럭시 노트북', '13인치 Full HD 디스플레이, 인텔 i7 프로세서, 16GB RAM', 1499000.00, 15, '전자제품'),
('LG 그램 노트북', '초경량 14인치, 배터리 20시간 사용', 1299000.00, 20, '전자제품'),
('애플 맥북 프로', 'M2 칩셋, 16인치 Retina 디스플레이', 2890000.00, 8, '전자제품'),
('로지텍 무선 마우스', '인체공학적 디자인, 블루투스 연결', 35000.00, 100, '액세서리'),
('로지텍 기계식 키보드', '체리 MX 스위치, RGB 백라이트', 159000.00, 50, '액세서리'),
('삼성 모니터 27인치', 'QHD 해상도, 144Hz 주사율', 399000.00, 30, '디스플레이'),
('LG 모니터 32인치', '4K UHD, HDR 지원', 599000.00, 25, '디스플레이'),
('소니 무선 헤드폰', '노이즈 캔슬링, 30시간 재생', 349000.00, 40, '오디오'),
('애플 에어팟 프로', '액티브 노이즈 캔슬링, 공간 오디오', 329000.00, 60, '오디오'),
('삼성 외장 SSD 1TB', 'USB 3.2 Gen2, 전송속도 1050MB/s', 129000.00, 80, '저장장치');

-- 사용자 테이블 (추가 예제)
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '사용자명',
    email VARCHAR(100) NOT NULL UNIQUE COMMENT '이메일',
    full_name VARCHAR(100) COMMENT '실명',
    phone VARCHAR(20) COMMENT '전화번호',
    address TEXT COMMENT '주소',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT '상태 (ACTIVE, INACTIVE, SUSPENDED)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '가입일시',
    last_login_at DATETIME COMMENT '마지막 로그인',
    INDEX idx_username (username),
    INDEX idx_email (email),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='사용자 테이블';

-- 사용자 샘플 데이터
INSERT INTO users (username, email, full_name, phone, address, status) VALUES
('hong123', 'hong@example.com', '홍길동', '010-1234-5678', '서울특별시 강남구', 'ACTIVE'),
('kim456', 'kim@example.com', '김철수', '010-2345-6789', '서울특별시 서초구', 'ACTIVE'),
('lee789', 'lee@example.com', '이영희', '010-3456-7890', '경기도 성남시', 'ACTIVE'),
('park012', 'park@example.com', '박민수', '010-4567-8901', '인천광역시 남동구', 'ACTIVE'),
('choi345', 'choi@example.com', '최지은', '010-5678-9012', '부산광역시 해운대구', 'INACTIVE');

-- 주문 테이블 (추가 예제)
CREATE TABLE orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '사용자 ID',
    order_number VARCHAR(50) NOT NULL UNIQUE COMMENT '주문번호',
    total_amount DECIMAL(12, 2) NOT NULL COMMENT '총 주문금액',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT '주문상태',
    payment_method VARCHAR(50) COMMENT '결제수단',
    shipping_address TEXT COMMENT '배송주소',
    order_date DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '주문일시',
    shipped_date DATETIME COMMENT '발송일시',
    delivered_date DATETIME COMMENT '배송완료일시',
    INDEX idx_user_id (user_id),
    INDEX idx_order_number (order_number),
    INDEX idx_status (status),
    INDEX idx_order_date (order_date),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문 테이블';

-- 주문 샘플 데이터
INSERT INTO orders (user_id, order_number, total_amount, status, payment_method, shipping_address, order_date) VALUES
(1, 'ORD-2024-001', 1499000.00, 'DELIVERED', '신용카드', '서울특별시 강남구 테헤란로 123', '2024-01-15 10:30:00'),
(2, 'ORD-2024-002', 194000.00, 'SHIPPED', '계좌이체', '서울특별시 서초구 반포대로 456', '2024-01-16 14:20:00'),
(3, 'ORD-2024-003', 2890000.00, 'PENDING', '신용카드', '경기도 성남시 분당구 판교로 789', '2024-01-17 09:15:00'),
(1, 'ORD-2024-004', 678000.00, 'DELIVERED', '카카오페이', '서울특별시 강남구 테헤란로 123', '2024-01-18 16:45:00'),
(4, 'ORD-2024-005', 349000.00, 'SHIPPED', '신용카드', '인천광역시 남동구 논현대로 321', '2024-01-19 11:30:00');

-- 설정 관리 테이블 생성 (애플리케이션 내부에서 자동 생성되지만 참고용)
CREATE TABLE index_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    index_name VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(500),
    source_table_name VARCHAR(255) NOT NULL,
    number_of_shards INT NOT NULL DEFAULT 1,
    number_of_replicas INT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auto_sync BOOLEAN NOT NULL DEFAULT FALSE,
    sync_interval_minutes INT,
    last_sync_time DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE field_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    index_config_id BIGINT NOT NULL,
    source_column_name VARCHAR(255) NOT NULL,
    target_field_name VARCHAR(255),
    field_type VARCHAR(50) NOT NULL,
    analyzer VARCHAR(100),
    indexed BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    FOREIGN KEY (index_config_id) REFERENCES index_config(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sync_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    index_config_id BIGINT NOT NULL,
    index_name VARCHAR(255) NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME,
    status VARCHAR(20) NOT NULL,
    records_processed BIGINT DEFAULT 0,
    records_succeeded BIGINT DEFAULT 0,
    records_failed BIGINT DEFAULT 0,
    error_message TEXT,
    duration_ms BIGINT,
    INDEX idx_index_config_id (index_config_id),
    INDEX idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
