-- ─────────────────────────────────────────────────────────────────────────────
-- 01-init.sql — chạy MỘT lần khi volume Postgres trống lần đầu.
-- Sửa file này sau đó sẽ KHÔNG chạy lại; phải `docker compose down -v` để reset.
-- ─────────────────────────────────────────────────────────────────────────────

-- Database riêng cho Keycloak (tách khỏi database chargeops của app)
CREATE DATABASE keycloak;

-- Bật extension cho database chargeops (database hiện tại lúc init)
CREATE EXTENSION IF NOT EXISTS postgis;   -- proximity search
-- pgvector (RAG chatbot - FR15) tạm hoãn: image postgis/postgis:16-3.4 chưa có
-- extension 'vector'. Sẽ bật lại khi đổi sang image có pgvector.
-- CREATE EXTENSION IF NOT EXISTS vector;
