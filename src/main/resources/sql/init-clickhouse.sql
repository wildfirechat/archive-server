-- ============================================
-- 野火im消息归档服务 - ClickHouse 初始化脚本
-- 单同步节点 + 多查询节点架构
-- ============================================

-- 1. 消息归档表
-- 按 user_hash (userId.hashCode() % 128) 分成128个分区
CREATE TABLE IF NOT EXISTS message_archive (
    mid UInt64,
    sender_id String,
    conv_type UInt8,
    conv_target String,
    conv_line UInt32,
    content_type UInt32,
    content_data String,
    searchable_key Nullable(String),
    user_id String,
    message_dt DateTime64(3),
    user_hash UInt8,
    _version UInt64 DEFAULT toUnixTimestamp64Milli(now64())
) ENGINE = ReplacingMergeTree(_version)
PARTITION BY user_hash
ORDER BY (user_id, message_dt, mid);

-- 2. 同步进度表
-- 记录每个用户消息表的同步进度
CREATE TABLE IF NOT EXISTS archive_progress (
    table_name String,
    last_mid UInt64,
    updated_at DateTime64(3)
) ENGINE = ReplacingMergeTree(updated_at)
ORDER BY table_name;

-- ============================================
-- 常用查询视图
-- ============================================

-- 3. 同步进度视图
CREATE OR REPLACE VIEW v_sync_progress AS
SELECT 
    table_name,
    last_mid,
    updated_at,
    toInt32(replaceRegexpOne(table_name, 't_user_messages_(\\d+)', '\\1')) as table_index
FROM archive_progress FINAL
ORDER BY table_index;

-- 4. 归档统计视图
CREATE OR REPLACE VIEW v_archive_stats AS
SELECT 
    count() as total_tables,
    max(last_mid) as max_last_mid,
    min(last_mid) as min_last_mid,
    max(updated_at) as last_update
FROM archive_progress FINAL;
