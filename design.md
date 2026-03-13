# 野火IM消息归档服务 - 设计文档

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0 | 2024 | 单同步节点 + 多查询节点架构 |
| 1.1 | 2024 | 添加 n-gram 搜索索引、优化缓存策略 |

## 1. 架构概述

### 1.1 系统架构

采用**单同步节点 + 多查询节点**的读写分离架构，通过配置区分节点类型，简化系统复杂度。

```
┌─────────────────────────────────────────────────────────────────┐
│                         部署架构                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌───────────────┐                                           │
│   │   负载均衡     │                                           │
│   │  (Nginx/SLB)  │                                           │
│   └───────┬───────┘                                           │
│           │                                                     │
│   ┌───────┴───────┬───────────────────┐                       │
│   │               │                   │                       │
│   ▼               ▼                   ▼                       │
│ ┌────────┐   ┌────────┐        ┌────────┐                    │
│ │ Query  │   │ Query  │        │ Query  │                    │
│ │ Node 1 │   │ Node 2 │   ...  │ Node N │                    │
│ │        │   │        │        │        │                    │
│ │ 只读API │   │ 只读API │        │ 只读API │                    │
│ └────┬───┘   └────┬───┘        └────┬───┘                    │
│      │            │                 │                         │
│      └────────────┼─────────────────┘                         │
│                   │                                            │
│                   ▼                                            │
│          ┌─────────────────┐                                  │
│          │   ClickHouse    │                                  │
│          │   (存储层)       │                                  │
│          └─────────────────┘                                  │
│                   ▲                                            │
│                   │                                            │
│          ┌────────┴────────┐                                  │
│          │                 │                                  │
│          ▼                 │                                  │
│   ┌──────────────┐         │                                  │
│   │   Sync Node  │─────────┘                                  │
│   │   (单节点)    │                                            │
│   │              │                                            │
│   │  数据同步任务 │◄── 128个用户消息表                          │
│   └──────────────┘                                            │
│          │                                                     │
│          ▼                                                     │
│   ┌──────────────┐                                            │
│   │  源数据库     │                                            │
│   │ (MySQL/Mongo)│                                            │
│   └──────────────┘                                            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 节点类型说明

| 类型 | 数量限制 | 配置 | 主要职责 |
|------|---------|------|---------|
| **同步节点** | ⚠️ **只能1个** | `archive.task.enabled=true` | 从源数据库同步消息到 ClickHouse |
| **查询节点** | 可多个 | `archive.task.enabled=false`（默认） | 提供消息查询 API 服务 |

### 1.3 核心设计决策

| 决策项 | 方案 | 说明 |
|--------|------|------|
| 部署模式 | 单同步 + 多查询 | 简化逻辑，无需分布式锁和故障转移 |
| 节点区分 | 配置驱动 | `archive.task.enabled` 配置区分节点类型 |
| 幂等保证 | ReplacingMergeTree | 基于 `_version` 字段自动去重 |
| 进度记录 | 按表记录 last_mid | 128个用户消息表各维护自己的进度 |
| 消息缓存 | Caffeine | mid=-1 表示空消息，防止缓存穿透 |
| 写入优化 | 批量写入 | 可配置批大小，支持自动分块 |
| 搜索优化 | n-gram 布隆过滤器 | 支持模糊搜索，Java层二次过滤 |
| 时间控制 | 安静时间策略 | 非安静时间不执行同步，降低业务影响 |
| 时区处理 | 双时区策略 | 消息处理用 UTC，安静时间用本地时区 |

---

## 2. 时区处理设计

### 2.1 时区策略

服务采用**双时区策略**：
- **消息处理时间**：统一使用 **UTC**（messageDt、历史筛选、延迟计算等）
- **安静时间判断**：使用 **JVM 默认时区**（服务器本地时区）

### 2.2 各场景时区处理

| 场景 | 时区策略 | 说明 |
|------|---------|------|
| 安静时间判断 | `ZoneId.systemDefault()` | 配置如 `02:00` 按服务器本地时间理解 |
| 消息时间存储 (messageDt) | `ZoneId.of("UTC")` | ClickHouse 中统一存储 UTC 时间 |
| 历史消息时间筛选 | `ZoneId.of("UTC")` | 与 messageDt 存储时区一致 |
| 消息延迟计算 | `ZoneId.of("UTC")` | 使用 UTC 计算延迟 |
| 进度表更新时间 | `ZoneId.of("UTC")` | 统一使用 UTC 存储 |
| 健康检查时间戳 | ISO 8601 with offset | 明确标注时区偏移，如 `2024-01-15T10:30:00+08:00` |

### 2.3 配置建议

**安静时间配置示例：**

```yaml
archive:
  task:
    # 服务器在中国 (UTC+8)，希望凌晨 2-6 点执行同步
    quiet-time-start: "02:00"  # 北京时间凌晨2点 (UTC 18:00)
    quiet-time-end: "06:00"    # 北京时间早上6点 (UTC 22:00)
```

**Docker 容器时区设置：**

```dockerfile
# Dockerfile - 设置容器本地时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
```

### 2.4 注意事项

1. **安静时间配置**：按照服务器**本地时间**填写
2. **消息时间**：ClickHouse 中 `messageDt` 统一存储 UTC 时间
3. **源数据库**：确保 MySQL/MongoDB 的 `_dt` 字段能正确转换为 UTC
4. **跨时区查询**：API 返回的 `messageDt` 为 UTC 时间，前端需要自行转换显示

---

## 3. 表结构设计

### 2.1 message_archive（消息归档表）

```sql
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
ORDER BY (user_id, conv_type, conv_target, conv_line, mid);
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| mid | UInt64 | 消息ID |
| sender_id | String | 发送者ID |
| conv_type | UInt8 | 会话类型（0-单聊, 1-群组, 2-聊天室, 3-频道） |
| conv_target | String | 会话目标（用户ID或群组ID） |
| conv_line | UInt32 | 会话线路（默认0） |
| content_type | UInt32 | 消息内容类型 |
| content_data | String | 消息内容（Base64编码） |
| searchable_key | String | 可搜索关键字（空字符串表示无） |
| user_id | String | 当前用户ID（用户视角） |
| message_dt | DateTime64(3) | 消息发送时间 |
| user_hash | UInt8 | user_id.hashCode() % 128，用于分区 |
| _version | UInt64 | 版本号（毫秒时间戳），用于去重 |

**索引说明：**

| 索引 | 类型 | 说明 |
|------|------|------|
| idx_searchable_key | tokenbf_v1(32768, 3, 0) | n-gram 布隆过滤器，支持模糊搜索 |

**设计说明：**
- **分区策略**：`PARTITION BY user_hash`，128个分区，与源表 `t_user_messages_N` 保持一致
- **排序键**：`ORDER BY (user_id, conv_type, conv_target, conv_line, mid)`，优化按用户+会话的查询
- **去重策略**：`ReplacingMergeTree(_version)`，相同主键保留 version 最大的记录
- **搜索优化**：n-gram 布隆过滤器索引，配合 Java 层二次过滤实现模糊搜索

### 2.2 archive_progress（同步进度表）

```sql
CREATE TABLE IF NOT EXISTS archive_progress (
    table_name String,
    last_mid UInt64,
    updated_at DateTime64(3)
) ENGINE = ReplacingMergeTree(updated_at)
ORDER BY table_name;
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| table_name | String | 源表名称，如 `t_user_messages_0` |
| last_mid | UInt64 | 最后同步的消息ID(mid) |
| updated_at | DateTime64(3) | 更新时间 |

**设计说明：**
- 每个用户消息表（共128个）在进度表中有一条记录
- 使用 `ReplacingMergeTree(updated_at)` 自动合并，保留最新记录

### 2.3 group_message_archive（群组消息归档表）

```sql
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
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| mid | UInt64 | 消息ID |
| sender_id | String | 发送者ID |
| gid | String | 群组ID |
| conv_line | UInt32 | 会话线路（默认0） |
| content_type | UInt32 | 消息内容类型 |
| content_data | String | 消息内容（Base64编码） |
| searchable_key | String | 可搜索关键字（空字符串表示无） |
| message_dt | DateTime64(3) | 消息发送时间 |
| group_hash | UInt8 | gid.hashCode() % 128，用于分区 |
| _version | UInt64 | 版本号（毫秒时间戳），用于去重 |

**索引说明：**

| 索引 | 类型 | 说明 |
|------|------|------|
| idx_searchable_key | tokenbf_v1(32768, 3, 0) | n-gram 布隆过滤器，支持模糊搜索 |

**设计说明：**
- **分区策略**：`PARTITION BY group_hash`，128个分区，与源表 `t_group_messages_N` 保持一致
- **排序键**：`ORDER BY (gid, conv_line, mid)`，优化按群组+消息ID的查询
- **去重策略**：`ReplacingMergeTree(_version)`，相同主键保留 version 最大的记录
- **搜索优化**：n-gram 布隆过滤器索引，配合 Java 层二次过滤实现模糊搜索
- **开启条件**：需要在配置中设置 `archive.task.sync-group-enabled=true`

---

## 4. 数据同步流程

### 3.1 同步流程图

```
┌─────────────────────────────────────────────────────────────┐
│                      同步节点启动                            │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │  检查配置               │
              │  archive.task.enabled? │
              └───────────┬────────────┘
                          │
              ┌───────────▼────────────┐
              │         是              │
              │  打印 SYNC 节点警告     │
              │  "只能启动一个！"        │
              └───────────┬────────────┘
                          │
                          ▼
              ┌────────────────────────┐
              │   启动归档任务线程       │
              │   (单线程循环处理)       │
              └───────────┬────────────┘
                          │
                          ▼
              ┌────────────────────────┐
              │  检查安静时间            │
              │  非安静时间则休息        │
              └───────────┬────────────┘
                          │
                          ▼
              ┌────────────────────────┐
              │  遍历 128 个用户消息表   │
              │  for i = 0 to 127      │
              └───────────┬────────────┘
                          │
                          ▼
         ┌────────────────────────────────┐
         │      处理单个表 (批次循环)       │
         │  ┌──────────────────────────┐  │
         │  │ 1. 从 progress 表读取    │  │
         │  │    last_mid              │  │
         │  └──────────┬───────────────┘  │
         │             │                   │
         │             ▼                   │
         │  ┌──────────────────────────┐  │
         │  │ 2. 从源表读取一批数据     │  │
         │  │    _mid > last_mid       │  │
         │  │    limit = batch_size    │  │
         │  └──────────┬───────────────┘  │
         │             │                   │
         │             ▼                   │
         │  ┌──────────────────────────┐  │
         │  │ 3. 查询消息内容          │  │
         │  │    缓存防穿透(mid=-1)    │  │
         │  └──────────┬───────────────┘  │
         │             │                   │
         │             ▼                   │
         │  ┌──────────────────────────┐  │
         │  │ 4. 合并为宽表记录         │  │
         │  │  记录缺失消息ID           │  │
         │  └──────────┬───────────────┘  │
         │             │                   │
         │             ▼                   │
         │  ┌──────────────────────────┐  │
         │  │ 5. 批量写入 ClickHouse   │  │
         │  │    ReplacingMergeTree    │  │
         │  └──────────┬───────────────┘  │
         │             │                   │
         │             ▼                   │
         │  ┌──────────────────────────┐  │
         │  │ 6. 写入成功后更新进度     │  │
         │  │    last_mid = 本批最大mid │  │
         │  └──────────┬───────────────┘  │
         │             │                   │
         │             ▼                   │
         │  ┌──────────────────────────┐  │
         │  │ 本表还有数据？            │  │
         │  │ 是 ────────┐             │  │
         │  │ 否          │ 继续下一批   │  │
         │  └──────┬─────┘             │  │
         │         │                    │  │
         │         ▼                    │  │
         │  继续处理下一个表 (i+1)        │  │
         └────────────────────────────────┘
                          │
                          ▼
              ┌────────────────────────┐
              │   所有表处理完成？       │
              │  是 ────────┐           │
              │  否          │ 休息60秒   │
              └──────┬──────┘ 继续下一轮 │
                     │                   │
                     ▼                   │
              ┌────────────────────────┐  │
              │      结束              │  │
              │ (停机信号或异常退出)    │◄─┘
              └────────────────────────┘
```

### 3.2 安静时间策略

```
┌─────────────────────────────────────────────────────────────┐
│                    安静时间处理逻辑                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   配置参数：                                                │
│   - archive.task.quiet-time-start: "02:00"                 │
│   - archive.task.quiet-time-end: "06:00"                   │
│                                                             │
│   逻辑：                                                    │
│   ┌──────────────┐                                         │
│   │ 当前时间在     │──是──► ┌──────────────┐               │
│   │ 安静时间内？   │        │ 执行同步任务   │               │
│   └──────────────┘         └──────────────┘               │
│          │                                                 │
│          否                                                │
│          ▼                                                 │
│   ┌──────────────┐                                         │
│   │ 跳过本轮      │                                         │
│   │ 休息等待      │                                         │
│   └──────────────┘                                         │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 批处理流程

```
┌─────────────────────────────────────────────────────────────┐
│                    doArchiveBatch 流程                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  输入：tableName, tableIndex, batchSize                    │
│                                                             │
│  1. [读取进度]                                              │
│     SELECT last_mid FROM archive_progress                   │
│     WHERE table_name = ?                                    │
│                                                             │
│  2. [读取用户消息]                                          │
│     SELECT * FROM t_user_messages_{index}                   │
│     WHERE _mid > ? LIMIT batchSize                          │
│                                                             │
│  3. [去重 mid]                                              │
│     Set<Long> mids = userMessages.stream()                  │
│                     .map(UserMessage::getMid)               │
│                     .collect(toSet())                      │
│                                                             │
│  4. [查询消息内容 - 带缓存防穿透]                            │
│     for mid in mids:                                        │
│         msg = messageCache.get(mid)                         │
│         // 缓存返回的 Message 中 mid=-1 表示不存在           │
│                                                             │
│  5. [合并宽表数据]                                          │
│     for userMessage in userMessages:                        │
│         message = messageMap.get(userMessage.mid)          │
│         if message != null && message.mid != -1:           │
│             archivedMessage = merge(message, userMessage)  │
│         else:                                               │
│             recordMissingMessage(mid)  // 记录缺失消息       │
│                                                             │
│  6. [批量写入]                                              │
│     if not archivedMessages.empty:                          │
│         INSERT INTO message_archive                         │
│         // 写入成功后更新进度                               │
│                                                             │
│  7. [更新进度]                                              │
│     lastMid = userMessages.last().mid                      │
│     INSERT INTO archive_progress                            │
│     VALUES (tableName, lastMid, now())                      │
│                                                             │
│  返回：hasMore, processedCount, missingCount                │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 5. 搜索设计

### 4.1 n-gram 布隆过滤器搜索

```
┌─────────────────────────────────────────────────────────────┐
│                    消息搜索流程                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. [构建查询]                                              │
│     SELECT * FROM message_archive                           │
│     WHERE user_id = ?                                       │
│       AND searchable_key LIKE '%keyword%'                   │
│       AND ... 其他条件                                      │
│     LIMIT queryLimit (预留 50% 缓冲)                        │
│                                                             │
│  2. [ClickHouse 执行]                                       │
│     ┌─────────────────────┐                                │
│     │ 使用 tokenbf_v1     │                                │
│     │ 布隆过滤器索引      │                                │
│     │ 快速预过滤          │                                │
│     └──────────┬──────────┘                                │
│                │                                            │
│                ▼                                            │
│     返回候选集（可能包含假阳性）                            │
│                                                             │
│  3. [Java 层二次过滤]                                       │
│     for candidate in candidates:                            │
│         if candidate.searchable_key                         │
│            .toLowerCase()                                   │
│            .contains(keyword.toLowerCase()):               │
│             add to result                                   │
│         if result.size >= limit:                            │
│             break                                           │
│                                                             │
│  4. [返回结果]                                              │
│     返回精确匹配的前 limit 条结果                           │
│                                                             │
│  注：假阳性率约 1-10%，可通过增大布隆过滤器大小降低          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 索引参数调优

| 参数 | 当前值 | 说明 | 调优建议 |
|------|--------|------|---------|
| 布隆过滤器大小 | 32768 (32KB) | 每个 granule 的布隆过滤器大小 | 增大可降低假阳性率，但占用更多存储 |
| n-gram 长度 | 3 | 每 3 个字符一组 | 3 适合中文，英文可调整为 4-5 |
| 哈希函数数 | 0 (自动) | 0 表示自动计算 | 一般不需要调整 |
| GRANULARITY | 4 | 每 4 个 granule 创建一个索引块 | 减小可提高精度，增大可减少索引大小 |

---

## 6. 缓存设计

### 5.1 消息缓存（防穿透）

```
┌─────────────────────────────────────────────────────────────┐
│                    消息缓存策略                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  缓存配置：                                                 │
│  - 最大容量：1000 条                                        │
│  - 过期时间：10 秒                                          │
│  - 缓存值：Message 对象（非 Optional）                      │
│                                                             │
│  防穿透设计：                                               │
│  ┌─────────────────────────────────────────┐               │
│  │ loadMessageFromSource(Long mid)         │               │
│  │                                         │               │
│  │  if mid 不存在或查询失败:               │               │
│  │     return Message(mid=-1)  // 空对象   │               │
│  │  else:                                  │               │
│  │     return 真实 Message 对象            │               │
│  └─────────────────────────────────────────┘               │
│                                                             │
│  使用检查：                                                 │
│  Message msg = messageCache.get(mid);                      │
│  if (msg != null && msg.getMid() != -1) {                  │
│      // 消息存在                                            │
│  } else {                                                   │
│      // 消息不存在（缓存穿透防护）                          │
│  }                                                          │
│                                                             │
│  优势：                                                     │
│  1. 防止缓存穿透 - 不存在的消息也会被缓存                   │
│  2. 减少重复查询 - 同一不存在的消息不会反复查询数据库       │
│  3. 简化代码 - 直接检查 mid 是否为 -1                       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 7. 类结构设计

### 6.1 核心类图

```
┌─────────────────────────────────────────────────────────────────┐
│                          Service 层                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────┐                               │
│  │   <<interface>>             │                               │
│  │   ArchiveService            │                               │
│  ├─────────────────────────────┤                               │
│  │ + isRunning(): boolean      │                               │
│  └─────────────┬───────────────┘                               │
│                │                                                │
│                │ implements                                     │
│                ▼                                                │
│  ┌─────────────────────────────┐                               │
│  │   ArchiveServiceImpl        │                               │
│  ├─────────────────────────────┤                               │
│  │ - nodeId: String            │                               │
│  │ - running: AtomicBoolean    │                               │
│  │ - shuttingDown: AtomicBoolean│                              │
│  │ - messageCache: LoadingCache│  // 缓存防穿透                │
│  │ - missingMessageCount: LongAdder                           │
│  │ - recentMissingMids: Set<Long>                             │
│  ├─────────────────────────────┤                               │
│  │ + init(): void              │  @PostConstruct               │
│  │ + destroy(): void           │  @PreDestroy                  │
│  │ - doArchiveCycle(): boolean │  // 一轮同步                 │
│  │ - doArchiveBatch(): BatchResult                           │
│  │ - isQuietTime(): boolean    │  // 安静时间检查             │
│  │ - calculateUserHash(): int  │  // 修复 abs 溢出问题        │
│  │ - getStatus(): Map          │  // 状态API                  │
│  └─────────────────────────────┘                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        Repository 层                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────┐                               │
│  │   ArchiveRepository         │                               │
│  ├─────────────────────────────┤                               │
│  │ - tableName: String         │                               │
│  ├─────────────────────────────┤                               │
│  │ + batchInsert(List): void   │  // 批量写入，自动分块        │
│  │ + fetchMessages(): List     │  // 查询消息                 │
│  │ + searchMessages(): List    │  // 搜索消息，带二次过滤     │
│  │ + getMaxMid(): Long         │  // 获取最大消息ID           │
│  │ + queryForObject(): T       │  // 通用查询                 │
│  │ + update(): int             │  // 通用更新                 │
│  └─────────────────────────────┘                               │
│                                                                 │
│  ┌─────────────────────────────┐                               │
│  │   <<interface>>             │                               │
│  │   MessageSource             │                               │
│  ├─────────────────────────────┤                               │
│  │ + fetchMessagesByMids(): Message                           │
│  │ + fetchUserMessagesByMidRange(): List                     │
│  └─────────────┬───────────────┘                               │
│                │                                                │
│       ┌────────┴────────┐                                      │
│       │                 │                                      │
│       ▼                 ▼                                      │
│  ┌──────────┐      ┌──────────┐                               │
│  │MySQLMessage│     │MongoMessage│                             │
│  │  Source   │      │  Source   │                               │
│  └──────────┘      └──────────┘                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                          Config 层                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────┐                               │
│  │   ArchiveProperties         │                               │
│  ├─────────────────────────────┤                               │
│  │ - sourceType: String        │  // mysql / mongodb          │
│  │ - mysql: MysqlConfig        │                               │
│  │ - mongodb: MongodbConfig    │                               │
│  │ - clickhouse: ClickhouseConfig                           │
│  │ - task: TaskConfig          │                               │
│  ├─────────────────────────────┤                               │
│  │ TaskConfig:                 │                               │
│  │ - enabled: boolean          │  // ⚠️ 关键配置               │
│  │ - quietTimeStart: String    │  // 安静时间开始             │
│  │ - quietTimeEnd: String      │  // 安静时间结束             │
│  │ - batchSize: int            │                               │
│  │ - batchIntervalMs: int      │                               │
│  │ - batchesPerCycle: int      │  // 每轮批次数               │
│  │ - shutdownTimeoutSec: int   │                               │
│  │ - syncHistoryDays: int      │  // 同步历史天数             │
│  │ - syncGroupEnabled: boolean │  // 同步群组消息开关         │
│  └─────────────────────────────┘                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                          Metrics 层                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────┐                               │
│  │   ArchiveMetrics            │                               │
│  ├─────────────────────────────┤                               │
│  │ - totalMessagesProcessed    │  // LongAdder                │
│  │ - totalMessagesFailed       │  // LongAdder                │
│  │ - currentProcessingLagMs    │  // AtomicLong               │
│  ├─────────────────────────────┤                               │
│  │ + recordMessagesProcessed() │                               │
│  │ + recordMessagesFailed()    │                               │
│  │ + recordProcessingLag()     │                               │
│  │ + generateReport(): String  │  // 生成统计报告             │
│  │ + getStatusSummary()        │  // 获取状态摘要             │
│  └─────────────────────────────┘                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 8. 部署方案

### 7.1 配置文件

详见 README.md 配置章节。

### 7.2 启动命令

**单节点模式（开发/测试）：**
```bash
java -jar wf-message-archive-server-1.0.0.jar \
  --server.port=8080 \
  --archive.task.enabled=true \
  --archive.clickhouse.url=jdbc:clickhouse://localhost:8123/default \
  --archive.mysql.url=jdbc:mysql://localhost:3306/im_db
```

**生产环境（1同步 + 2查询）：**
```bash
# 同步节点（只启动一个！）
java -jar wf-message-archive-server.jar \
  --server.port=8080 \
  --archive.task.enabled=true \
  --archive.task.batch-size=1000

# 查询节点 1
java -jar wf-message-archive-server.jar \
  --server.port=8081 \
  --archive.task.enabled=false

# 查询节点 2
java -jar wf-message-archive-server.jar \
  --server.port=8082 \
  --archive.task.enabled=false
```

---

## 9. ⚠️ 部署警告与注意事项

### 8.1 同步节点限制

**⚠️ 严重警告：只能启动一个同步节点！**

如果启动多个同步节点，会导致以下问题：

| 问题 | 影响 | 解决方法 |
|------|------|---------|
| 数据重复写入 | ClickHouse 存储冗余 | 手动清理重复数据 |
| ClickHouse 资源竞争 | 写入性能下降 | 停止多余的同步节点 |
| 源数据库压力过大 | 影响线上业务 | 立即停止多余节点 |
| 进度表冲突 | 数据不一致风险 | 重启同步节点 |

### 8.2 切换同步节点流程

```
1. 停止旧同步节点
   直接停止进程（kill -15 <pid>），会触发优雅停机
   
2. 检查 ClickHouse 中的进度
   SELECT * FROM archive_progress FINAL ORDER BY table_name
   
3. 启动新同步节点
   java -jar ... --archive.task.enabled=true
   
4. 验证新节点正常运行
   curl http://new-node:8080/api/admin/status
```

### 8.3 日志检查清单

**启动时检查（必须只有1个）：**
```bash
# 检查同步节点启动数量（应该只有1个）
grep "This is a SYNC node" /var/log/archive/*.log | wc -l

# 检查是否有多个同步节点警告
grep "WARNING.*SYNC node" /var/log/archive/*.log
```

**运行时检查：**
```bash
# 检查同步任务是否运行中
grep "Archive task started" /var/log/archive/*.log

# 检查是否有错误
grep -i "error\|exception" /var/log/archive/*.log

# 检查处理速率
grep "Metrics Report" /var/log/archive/*.log | tail -5
```

---

## 10. 健康检查接口

详见 README.md 健康检查章节。

---

## 11. 监控与告警

### 10.1 关键指标

| 指标 | 采集方式 | 告警阈值 | 处理建议 |
|------|---------|---------|---------|
| 处理速率 | 日志 | 长时间为0 | 检查同步节点是否运行 |
| 处理延迟 | metrics.currentLagMs | > 5分钟 | 检查源数据库或 ClickHouse |
| 错误率 | metrics.totalFailed / totalProcessed | > 1% | 查看错误日志 |
| 同步节点数量 | 日志关键字 | > 1 | 立即停止多余的同步节点 |
| 缺失消息数 | /api/admin/status | 持续增长 | 检查源数据库消息清理策略 |

### 10.2 ClickHouse 监控查询

```sql
-- 查看同步进度
SELECT 
    table_name,
    last_mid,
    updated_at
FROM archive_progress FINAL 
ORDER BY table_name;

-- 查看各表进度统计
SELECT 
    count() as total_tables,
    max(last_mid) as max_progress,
    min(last_mid) as min_progress,
    max(updated_at) as last_update
FROM archive_progress FINAL;

-- 查看归档消息数量
SELECT count() FROM message_archive FINAL;

-- 查看最近24小时写入的消息数
SELECT count() 
FROM message_archive FINAL
WHERE message_dt > now() - INTERVAL 1 DAY;

-- 查看处理延迟最大的表
SELECT 
    table_name,
    last_mid,
    updated_at,
    dateDiff('minute', updated_at, now()) as lag_minutes
FROM archive_progress FINAL
ORDER BY lag_minutes DESC
LIMIT 10;

-- 查看搜索索引效率（假阳性率估算）
SELECT 
    count() as total_rows,
    countIf(searchable_key = '') as empty_searchable
FROM message_archive FINAL;
```

---

## 12. 故障排查

### 11.1 场景1：数据重复

**现象：** 查询时发现同一消息有多条记录

**原因：** 启动了多个同步节点

**解决步骤：**
```sql
-- 1. 停止所有同步节点

-- 2. 检查重复数据
SELECT mid, user_id, count() as cnt
FROM message_archive
GROUP BY mid, user_id
HAVING cnt > 1
LIMIT 10;

-- 3. 清理重复数据（使用 OPTIMIZE 强制合并）
OPTIMIZE TABLE message_archive FINAL;

-- 4. 只启动一个同步节点
```

### 11.2 场景2：同步进度停滞

**排查步骤：**
1. 检查同步节点状态：`GET /api/admin/status`
2. 查看同步节点日志是否有错误
3. 检查源数据库连接是否正常
4. 检查 ClickHouse 写入是否正常
5. 查看是否在安静时间之外
6. 查看 `archive_progress` 表的 `updated_at` 字段

### 11.3 场景3：查询性能下降

**可能原因：**
- ClickHouse 分区过多未合并
- 查询条件未使用索引字段
- n-gram 索引未生效

**优化建议：**
```sql
-- 定期执行 OPTIMIZE 合并分区
OPTIMIZE TABLE message_archive FINAL;

-- 查询时使用索引字段
-- 好的查询：WHERE user_id = ? AND conv_target = ? AND mid > ?
-- 避免：WHERE content_data LIKE ?
```

### 11.4 场景4：搜索结果不准确或为空

**排查步骤：**
1. 检查 `searchable_key` 字段是否有数据
2. n-gram 索引要求搜索词长度 >= 3 个字符
3. 查看日志中的假阳性过滤统计
4. 执行 `OPTIMIZE TABLE message_archive FINAL` 重建索引
5. 检查是否所有消息都有 `searchable_key`

---

## 13. 数据安全保证

| 场景 | 机制 | 结果 |
|------|------|------|
| 正常同步 | 顺序读取，批量写入 | 正常归档 |
| 同步节点重启 | 从 last_id 继续 | 无遗漏，可能少量重复 |
| 幂等写入 | ReplacingMergeTree 引擎 | 重复数据自动去重 |
| 批量失败 | 不更新进度，下次重试 | 数据不丢失 |
| 优雅停机 | 等待当前批次完成 | 数据完整性保证 |
| 消息缺失 | 记录缺失消息ID | 便于审计和排查 |
| 缓存穿透 | 返回 mid=-1 的空对象 | 防止重复查询 |

---

## 14. 性能优化建议

### 13.1 批处理参数调优

```yaml
# 小内存环境（< 4GB）
archive:
  task:
    batch-size: 200
    batch-interval-ms: 2000
    batches-per-cycle: 5
  clickhouse:
    max-batch-size: 1000

# 大内存环境（> 8GB）
archive:
  task:
    batch-size: 2000
    batch-interval-ms: 500
    batches-per-cycle: 20
  clickhouse:
    max-batch-size: 10000
```

### 13.2 ClickHouse 优化

```sql
-- 定期优化表（可配置为定时任务）
OPTIMIZE TABLE message_archive FINAL;

-- 查看表统计
SELECT 
    partition,
    count() as parts,
    sum(rows) as total_rows,
    formatReadableSize(sum(data_uncompressed_bytes)) as uncompressed,
    formatReadableSize(sum(data_compressed_bytes)) as compressed
FROM system.parts
WHERE table = 'message_archive' AND active
GROUP BY partition
ORDER BY partition;

-- 查看索引效率
SELECT 
    table,
    name,
    type,
    expr
FROM system.data_skipping_indices
WHERE table = 'message_archive';
```

### 13.3 n-gram 索引调优

如果假阳性率过高（>20%），可以调整索引参数：

```sql
-- 重建表，增大布隆过滤器大小
CREATE TABLE message_archive_new (
    -- 相同字段定义
    INDEX idx_searchable_key searchable_key 
        TYPE tokenbf_v1(65536, 3, 0)  -- 增大到 64KB
        GRANULARITY 2  -- 减小粒度提高精度
) ENGINE = ReplacingMergeTree(_version)
PARTITION BY user_hash
ORDER BY (user_id, conv_type, conv_target, conv_line, mid);

-- 迁移数据
INSERT INTO message_archive_new SELECT * FROM message_archive FINAL;

-- 交换表名
RENAME TABLE message_archive TO message_archive_old;
RENAME TABLE message_archive_new TO message_archive;
```
