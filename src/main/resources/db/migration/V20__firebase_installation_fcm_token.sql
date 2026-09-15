-- SAGA V20: first-class FCM registration token on firebase_installation.
-- V1-V19 remain immutable. Additive/safe. utf8mb4.
--
-- firebase_installation_id (V1) is the Firebase Installation ID (FID).
-- It is not the FCM registration token. Do not overload it.
--
-- MySQL 8 table default is utf8mb4_0900_ai_ci. FID and FCM tokens are opaque
-- identifiers and must compare case-sensitively, so V20 switches those two
-- columns to utf8mb4_bin (same binary collation used for git ref names in V18).
-- V1 is not edited; MODIFY only changes collation/charset of the existing FID column.
--
-- fcm_token is nullable so pre-V20 rows survive. Registration always writes a token.
-- UNIQUE allows multiple NULLs (MySQL). A non-null token maps to one installation.
--
-- platform is WEB/ANDROID/IOS at the application layer (VARCHAR, no CHECK).
-- Nullable so surviving V1 rows remain valid.

ALTER TABLE firebase_installation
    MODIFY COLUMN firebase_installation_id VARCHAR(255)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ADD COLUMN fcm_token VARCHAR(512)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER firebase_installation_id,
    ADD COLUMN platform VARCHAR(16) NULL AFTER fcm_token,
    ADD UNIQUE KEY uk_firebase_installation_fcm_token (fcm_token);
