-- 로컬 시드: 테넌트 1 · 이벤트 1(승인됨) · 쿠폰 10종 · 재고 10
-- 공개키(event_auth_key)는 local/scripts/gen-test-keys.sh 가 만드는 09_local_test_key.sql 이 넣는다.
SET NAMES utf8mb4;

SET @tenant_id = 'poctenant001';

USE tetra_platform;

INSERT INTO tenant (tenant_id, name, root_domain, email, contact_name, account_status, provisioning_status)
VALUES (@tenant_id, '(주) PoC 테스트사', 'poc-tenant.example.com', 'tenant@poc-tenant.example.com', '테스트 담당자',
        'ACTIVE', 'READY');

INSERT INTO operator (operator_id, email, contact_name, password)
VALUES (1, 'operator@tetra.local', '로컬 운영자', '{noop}local-only');

INSERT INTO traffic_plan (plan_code, max_capacity, hourly_price, hosting_hourly_price) VALUES
('S', 10000, 0, 0), ('M', 30000, 0, 0), ('L', 50000, 0, 0), ('XL', 100000, 0, 0);

INSERT INTO template (template_id, event_type, description, file_path)
VALUES (1, 'COUPON', '쿠폰 발급 이벤트', 'apps/coupon/');

INSERT INTO event_registry (event_id, tenant_id, name, event_type, traffic_plan_code, hosting_start_at, start_at,
                            applied_at, processed_at, application_status, event_status, hosting_status)
VALUES (1, @tenant_id, 'PoC 쿠폰 발급 이벤트', 'COUPON', 'L', '2026-09-26 10:00:00', '2026-09-29 10:00:00',
        '2026-09-20 10:00:00', '2026-09-21 10:00:00', 'APPROVED', 'OPEN', 'HOSTING');

USE t_poctenant001;

INSERT INTO event (event_id, tenant_id, name, event_type, template_id, layout_config, banner_image_path,
                   start_at, end_at, hosting_start_at, hosting_end_at, endpoint_url, traffic_plan_code,
                   stock_count, slug, applied_at, processed_by, processed_at,
                   application_status, hosting_status, event_status)
VALUES (1, @tenant_id, 'PoC 쿠폰 발급 이벤트', 'COUPON', 1,
        '{"title":{"h":"CENTER","v":"TOP"},"banner":{"h":"CENTER","v":"TOP"},"couponList":{"h":"CENTER","v":"MIDDLE"},"issueButton":{"h":"CENTER","v":"BOTTOM"},"theme":"WHITE"}',
        'assets/banners/1/banner.webp',
        '2026-09-29 10:00:00', '2030-12-31 10:00:00', '2026-09-26 10:00:00', '2030-12-31 11:00:00',
        '', 'L', 10, 'k7f2q9xa', '2026-09-20 10:00:00', 1, '2026-09-21 10:00:00',
        'APPROVED', 'HOSTING', 'OPEN');

INSERT INTO coupon (coupon_id, event_id, tenant_id, name, description) VALUES
(1,  1, @tenant_id, 'PoC 쿠폰 1',  'PoC 쿠폰입니다.'),
(2,  1, @tenant_id, 'PoC 쿠폰 2',  'PoC 쿠폰입니다.'),
(3,  1, @tenant_id, 'PoC 쿠폰 3',  'PoC 쿠폰입니다.'),
(4,  1, @tenant_id, 'PoC 쿠폰 4',  'PoC 쿠폰입니다.'),
(5,  1, @tenant_id, 'PoC 쿠폰 5',  'PoC 쿠폰입니다.'),
(6,  1, @tenant_id, 'PoC 쿠폰 6',  'PoC 쿠폰입니다.'),
(7,  1, @tenant_id, 'PoC 쿠폰 7',  'PoC 쿠폰입니다.'),
(8,  1, @tenant_id, 'PoC 쿠폰 8',  'PoC 쿠폰입니다.'),
(9,  1, @tenant_id, 'PoC 쿠폰 9',  'PoC 쿠폰입니다.'),
(10, 1, @tenant_id, 'PoC 쿠폰 10', 'PoC 쿠폰입니다.');
