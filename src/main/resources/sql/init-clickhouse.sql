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
    searchable_key String DEFAULT '',
    user_id String,
    message_dt DateTime64(3),
    user_hash UInt8,
    _version UInt64 DEFAULT toUnixTimestamp64Milli(now64()),
    INDEX idx_searchable_key searchable_key TYPE tokenbf_v1(32768, 3, 0) GRANULARITY 4
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

-- 3. 群组消息归档表（超级群组）
-- 按 group_hash (gid.hashCode() % 128) 分成128个分区
CREATE TABLE IF NOT EXISTS group_message_archive (
    mid UInt64,
    sender_id String,
    gid String,
    conv_line UInt32,
    content_type UInt32,
    content_data String,
    searchable_key String DEFAULT '',
    message_dt DateTime64(3),
    group_hash UInt8,
    _version UInt64 DEFAULT toUnixTimestamp64Milli(now64()),
    INDEX idx_searchable_key searchable_key TYPE tokenbf_v1(32768, 3, 0) GRANULARITY 4
) ENGINE = ReplacingMergeTree(_version)
PARTITION BY group_hash
ORDER BY (gid, conv_line, mid);

-- ============================================
-- 常用查询视图
-- ============================================

-- 4. 同步进度视图
CREATE OR REPLACE VIEW v_sync_progress AS
SELECT 
    table_name,
    last_mid,
    updated_at,
    toInt32(replaceRegexpOne(table_name, 't_user_messages_(\\d+)', '\\1')) as table_index
FROM archive_progress FINAL
ORDER BY table_index;

-- 5. 归档统计视图
CREATE OR REPLACE VIEW v_archive_stats AS
SELECT 
    count() as total_tables,
    max(last_mid) as max_last_mid,
    min(last_mid) as min_last_mid,
    max(updated_at) as last_update
FROM archive_progress FINAL;

-- 6. 群组消息同步进度视图
CREATE OR REPLACE VIEW v_group_sync_progress AS
SELECT 
    table_name,
    last_mid,
    updated_at,
    toInt32(replaceRegexpOne(table_name, 't_group_messages_(\\d+)', '\\1')) as table_index
FROM archive_progress FINAL
WHERE table_name LIKE 't_group_messages_%'
ORDER BY table_index;
