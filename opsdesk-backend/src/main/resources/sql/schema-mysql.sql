-- ============================================================
--  Spring AI 会话记忆表（框架托管，非业务表）
--  用途：spring.ai.chat.memory.repository.jdbc 自动建表
--  说明：本文件与 OpsDesk_DDL_V1.sql 分开 ——
--        OpsDesk_DDL_V1.sql 是业务表 SSOT（17 张），本表由 Spring AI 框架自动创建/使用。
-- ============================================================

CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    `id`              BIGINT(19)   NOT NULL AUTO_INCREMENT,
    `conversation_id` VARCHAR(36)  NOT NULL COLLATE 'utf8mb4_general_ci',
    `content`         TEXT         NOT NULL COLLATE 'utf8mb4_general_ci',
    `type`            VARCHAR(10)  NOT NULL COLLATE 'utf8mb4_general_ci',
    `timestamp`       TIMESTAMP    NOT NULL,
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX` (`conversation_id`, `timestamp`) USING BTREE,
    CONSTRAINT TYPE_CHECK CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL'))
);
