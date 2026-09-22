-- ============================================================
-- 버스기사 기능 + 실제 버스 데이터 마이그레이션
-- Supabase Dashboard → SQL Editor 에서 실행
-- ============================================================

-- 1. users 테이블 role에 'driver' 추가
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
  CHECK (role IN ('user', 'admin', 'driver'));

-- 2. buses 테이블에 버스기사 운행 관련 컬럼 추가
ALTER TABLE buses
  ADD COLUMN IF NOT EXISTS is_running BOOLEAN DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS current_driver_id UUID REFERENCES users(id) ON DELETE SET NULL;

-- buses 인덱스
CREATE INDEX IF NOT EXISTS idx_buses_running ON buses(is_running);

-- 3. buses_with_routes 뷰에 is_running, current_driver_id 반영
-- 기존 뷰보다 컬럼 수가 줄어드는 변경은 CREATE OR REPLACE VIEW로 적용할 수 없다.
-- 새 Supabase 환경에서도 전체 마이그레이션을 처음부터 재생할 수 있도록 명시적으로 재생성한다.
DROP VIEW IF EXISTS buses_with_routes;
CREATE VIEW buses_with_routes AS
SELECT
  b.id,
  b.name,
  b.type,
  b.capacity,
  b.license_plate,
  b.status,
  b.is_running,
  b.current_driver_id,
  b.created_at,
  b.updated_at,
  r.id   AS route_id,
  r.name AS route_name,
  r.color AS route_color
FROM buses b
LEFT JOIN routes r ON b.current_route_id = r.id;

-- 4. 실제 버스 데이터 삽입 (이미 있으면 스킵)
INSERT INTO buses (id, name, type, capacity, license_plate, status, is_running)
VALUES
  ('campus-001', '학내순환 1호', 'shuttle', 45, '충남 71 가 1001', 'active', false),
  ('campus-002', '학내순환 2호', 'shuttle', 45, '충남 71 가 1002', 'active', false)
ON CONFLICT (id) DO NOTHING;

-- 5. 학내순환 노선 데이터 삽입
INSERT INTO routes (id, name, type, description, color, is_active)
VALUES
  ('00000000-0000-0000-0000-000000000001', '학내순환', 'shuttle', '후문 → 향3 → 향1 → 도서관 → 정문 순환', '#1E3B8A', true)
ON CONFLICT (id) DO NOTHING;

-- 6. 학내순환 정류장 데이터 삽입
INSERT INTO route_stops (route_id, stop_name, stop_order, latitude, longitude)
VALUES
  ('00000000-0000-0000-0000-000000000001', '후문',   1, 36.772760, 126.933816),
  ('00000000-0000-0000-0000-000000000001', '향3',    2, 36.768228, 126.935383),
  ('00000000-0000-0000-0000-000000000001', '향1',    3, 36.767905, 126.932505),
  ('00000000-0000-0000-0000-000000000001', '도서관', 4, 36.768856, 126.930700),
  ('00000000-0000-0000-0000-000000000001', '정문',   5, 36.769014, 126.927978)
ON CONFLICT DO NOTHING;

-- 7. 버스에 노선 연결
UPDATE buses
SET current_route_id = '00000000-0000-0000-0000-000000000001'
WHERE id IN ('campus-001', 'campus-002');

-- 확인
SELECT id, name, type, status, is_running FROM buses;
