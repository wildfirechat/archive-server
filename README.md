# 野火IM消息归档服务

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

采用**单同步节点 + 多查询节点**架构的消息归档服务。

## 核心特性

- ✅ **简化架构** - 单节点同步，无需分布式锁和复杂协调
- ✅ **读写分离** - 单节点写入，多节点查询
- ✅ **数据安全** - 基于last_id的顺序同步，幂等写入
- ✅ **消息缓存** - 缓存防穿透设计，减少数据库查询
- ✅ **安静时间** - 支持配置同步时间段，降低对业务影响
- ✅ **优雅停机** - 确保正在处理的数据完整性
- ✅ **健康检查** - 完善的探活接口，方便监控告警
- ✅ **n-gram搜索** - 布隆过滤器索引支持模糊搜索

## 架构设计说明

### 为什么是单同步节点？

我们采用**单同步节点 + 多查询节点**的架构，这是经过深思熟虑的设计选择：

#### 1. 多节点同步的困难

多节点同时同步数据会面临以下复杂问题：

| 问题 | 说明 | 解决复杂度 |
|------|------|-----------|
| **数据冲突** | 多个节点可能同时读取同一批数据，导致重复写入 | 需要分布式锁或事务协调 |
| **进度协调** | 多个节点需要协调同步进度，避免遗漏或重复 | 需要共享状态存储和一致性协议 |
| **顺序保证** | 消息需要按顺序同步，多节点难以保证全局顺序 | 需要复杂的排序和合并逻辑 |
| **故障转移** | 节点切换时需要确保数据完整性，防止丢失或重复 | 需要复杂的选主和状态恢复机制 |
| **网络分区** | 网络问题可能导致脑裂，多个节点同时认为自己是主节点 | 需要分布式共识算法（如 Raft）|

这些问题会显著增加系统的复杂度和维护成本。

#### 2. 单节点的简单性

单同步节点的优势：

- **无状态竞争**：只有一个节点写入，天然避免冲突
- **简单可靠**：无需分布式锁、选主、故障转移等复杂机制
- **易于调试**：出问题只需查看一个节点的日志
- **进度清晰**：单节点顺序处理，进度表即真实进度

#### 3. 实时性要求不高

消息归档服务的特点决定了它不需要高实时性：

- **离线分析为主**：主要用于历史消息查询和数据分析
- **分钟级延迟可接受**：同步延迟几分钟对业务影响很小
- **非关键路径**：不影响实时消息的发送和接收

#### 4. 监控 + 人工介入足够

对于单节点可能故障的情况，我们采用**监控告警 + 快速重启**的策略：

- **完善的探活接口**：`/api/health/*` 系列接口支持各种监控系统
- **快速故障发现**：处理延迟、错误率等指标实时监控
- **快速恢复**：节点故障时，重启服务或切换到备用节点即可
- **数据不丢失**：基于 `last_id` 的顺序同步，重启后从断点续传

#### 5. 成本收益分析

| 方案 | 复杂度 | 可靠性 | 维护成本 | 适用场景 |
|------|--------|--------|----------|----------|
| 单同步节点 | ⭐ 低 | 高（监控+人工） | 低 | ✅ 我们的场景 |
| 多节点协调同步 | ⭐⭐⭐ 高 | 很高 | 高 | 金融级实时系统 |

**结论**：对于消息归档这种实时性要求不高、可容忍分钟级中断的场景，单同步节点是性价比最高的选择。

## 架构图

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Query Node    │     │    Sync Node    │     │   Query Node    │
│    (API)        │     │    (Sync)       │     │    (API)        │
│                 │     │                 │     │                 │
│  启动多个        │     │  ⚠️ 只启动1个    │     │  启动多个        │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │                       │                       │
         └───────────────────────┼───────────────────────┘
                                 ▼
                    ┌─────────────────────┐
                    │     ClickHouse      │
                    │  ┌───────────────┐  │
                    │  │message_archive│  │  <- 消息归档表
                    │  └───────────────┘  │
                    │  ┌───────────────┐  │
                    │  │archive_progress│ │  <- 同步进度
                    │  └───────────────┘  │
                    └─────────────────────┘
```

## ⚠️ 部署警告

### 同步节点限制

**只能启动一个同步节点**（`archive.task.enabled=true`），否则会导致：
- 数据重复写入
- ClickHouse 资源竞争
- 源数据库压力过大

### 节点类型

| 类型 | 配置 | 数量 | 功能 |
|------|------|------|------|
| **同步节点** | `archive.task.enabled=true` | **只能1个** | 从源数据库同步消息到 ClickHouse |
| **查询节点** | `archive.task.enabled=false` | 可多个 | 提供消息查询 API |

## 快速开始

### 1. 环境要求

- Java 11+
- MySQL 8.0+ 或 MongoDB（消息源）
- ClickHouse 21.8+（归档存储）
- Maven 3.6+

### 2. 初始化 ClickHouse

```bash
clickhouse-client -d default < src/main/resources/sql/init-clickhouse.sql
```

创建的表：
- `message_archive` - 消息归档表（ReplacingMergeTree引擎，自动去重）
  - 包含 n-gram 布隆过滤器索引支持模糊搜索
  - ORDER BY (user_id, conv_type, conv_target, conv_line, mid)
- `archive_progress` - 同步进度表

### 3. 配置

**时区说明**：
- **消息时间**：统一使用 **UTC** 时间（messageDt、历史筛选、延迟计算）
- **安静时间**：使用 **服务器本地时区**（配置如 `02:00` 按本地时间理解）
- 健康检查时间戳返回 ISO 8601 格式（带时区偏移）

**同步节点配置（application-sync.yml）：**
```yaml
server:
  port: 8080

archive:
  # 源数据库类型：mysql 或 mongodb
  source-type: mysql
  
  # 任务配置
  task:
    enabled: true           # ⚠️ 启用同步（只能启动一个此节点）
    batch-size: 500         # 每批处理的消息数
    batch-interval-ms: 1000 # 批次间隔（毫秒）
    shutdown-timeout-sec: 30 # 优雅停机超时时间
    quiet-time-start: "02:00" # 安静时间开始
    quiet-time-end: "06:00"   # 安静时间结束
    batches-per-cycle: 10     # 每轮处理的批次数
    sync-history-days: 365    # 只同步 N 天前的消息（0表示全部）
  
  # ClickHouse 配置
  clickhouse:
    url: jdbc:clickhouse://localhost:8123/default
    username: default
    password: ""
    table: message_archive
    max-batch-size: 5000    # 单次最大写入数
  
  # MySQL 源配置
  mysql:
    url: jdbc:mysql://localhost:3306/im_db
    username: root
    password: password
  
  # MongoDB 源配置（如果 source-type=mongodb）
  mongodb:
    uri: mongodb://localhost:27017/wfchat
    database: wfchat
```

**查询节点配置（application-query.yml）：**
```yaml
server:
  port: 8081

archive:
  task:
    enabled: false          # 只提供查询服务
  clickhouse:
    url: jdbc:clickhouse://localhost:8123/default
    username: default
    password: ""
    table: message_archive
```

### 4. 启动服务

**单节点（开发/测试）：**
```bash
java -jar target/wf-message-archive-server-1.0.0.jar \
  --spring.profiles.active=sync
```

**生产环境（1同步 + 2查询）：**
```bash
# 同步节点（只启动一个！）
java -jar target/wf-message-archive-server-1.0.0.jar \
  --server.port=8080 \
  --archive.task.enabled=true

# 查询节点 1
java -jar target/wf-message-archive-server-1.0.0.jar \
  --server.port=8081 \
  --archive.task.enabled=false

# 查询节点 2
java -jar target/wf-message-archive-server-1.0.0.jar \
  --server.port=8082 \
  --archive.task.enabled=false
```

## 健康检查接口

服务提供完善的探活接口，方便集成到监控系统中：

### 1. 简单探活

```bash
GET /api/health/ping
```

**响应：** `pong`

**用途**：负载均衡健康检查

### 2. 基础健康检查

```bash
GET /api/health/check
```

**响应示例：**
```json
{
  "status": "UP",
  "timestamp": "2024-01-15T10:30:00",
  "nodeType": "SYNC",
  "clickhouse": "UP"
}
```

**用途**：服务存活检查，检查 ClickHouse 连接

### 3. 详细健康检查

```bash
GET /api/health/detail
```

**响应示例（同步节点）：**
```json
{
  "status": "UP",
  "timestamp": "2024-01-15T10:30:00",
  "nodeType": "SYNC",
  "clickhouse": "UP",
  "sync": {
    "running": true,
    "healthy": true,
    "lagMs": 5000,
    "totalProcessed": 1500000,
    "totalFailed": 0
  }
}
```

**状态说明：**
- `status: UP` - 健康
- `status: WARN` - 警告（如同步任务停止或延迟过高）
- `status: DOWN` - 故障（ClickHouse 连接失败）

### 4. Kubernetes 探活

```bash
# Readiness Probe - 服务是否准备好接收流量
GET /api/health/ready

# Liveness Probe - 服务是否存活
GET /api/health/live
```

### 5. 监控告警建议

**Prometheus + Alertmanager 配置示例：**

```yaml
# 检查服务是否存活
- alert: ArchiveServiceDown
  expr: up{job="archive-service"} == 0
  for: 1m
  labels:
    severity: critical
  annotations:
    summary: "Archive service is down"

# 检查 ClickHouse 连接
- alert: ClickHouseConnectionFailed
  expr: archive_health_status{component="clickhouse"} == 0
  for: 1m
  labels:
    severity: critical
  annotations:
    summary: "ClickHouse connection failed"

# 检查同步任务是否运行（仅同步节点）
- alert: SyncTaskNotRunning
  expr: archive_sync_running == 0
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Sync task is not running"

# 检查处理延迟（仅同步节点）
- alert: HighProcessingLag
  expr: archive_sync_lag_ms > 300000  # 5分钟
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Processing lag is {{ $value }}ms"
```

## API 文档

### 获取服务状态

```bash
GET /api/admin/status
```

**响应示例：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "nodeId": "server01-a1b2c3d4",
    "nodeType": "SYNC",
    "running": true,
    "shuttingDown": false,
    "metrics": {
      "totalProcessed": 1500000,
      "totalFailed": 0,
      "currentLagMs": 5000,
      "healthy": true
    },
    "missingMessageCount": 0,
    "recentMissingMids": []
  }
}
```

### 获取消息列表

```bash
POST /api/messages/fetch
Header: authCode: xxxxxx

{
  "convType": 0,        // 会话类型：0单聊/1群聊/2聊天室/3频道（可选）
  "convTarget": "user1", // 会话目标（可选）
  "convLine": 0,        // 会话线路，默认0（可选）
  "contentType": 1,     // 消息内容类型（可选）
  "startMid": 1000,     // 起始消息ID（可选）
  "before": true,       // true=向前查(更早)，false=向后查(更新)，默认true
  "limit": 20           // 条数，默认20，最大100
}
```

**响应示例：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "messages": [
      {
        "mid": 999,
        "senderId": "user1",
        "convType": 1,
        "convTarget": "group123",
        "convLine": 0,
        "contentType": 1,
        "payload": {
          "type": 1,
          "content": "Hello World",
          "searchableContent": "Hello World"
        },
        "searchableKey": "Hello World",
        "userId": "user1",
        "messageDt": "2024-01-15T10:30:00"
      }
    ],
    "hasMore": true,
    "nextStartMid": 998
  }
}
```

**说明：** 消息内容已解析为 `payload` 对象，无需 Base64 解码。详情参考 API.md 文档。

### 搜索消息

使用 n-gram 布隆过滤器索引 + Java 层二次过滤实现模糊搜索。

```bash
POST /api/messages/search
Header: authCode: xxxxxx

{
  "keyword": "项目进度",   // 搜索关键字（必填）
  "convType": 0,          // 其他筛选条件同 fetch（可选）
  "limit": 20
}
```

**实现说明：**
1. ClickHouse 使用 `tokenbf_v1` 布隆过滤器索引快速预过滤
2. Java 层对结果进行二次精确过滤，排除假阳性
3. 搜索词长度建议 >= 3 个字符以获得最佳性能

### 获取部署说明

```bash
GET /api/admin/deployment
```

### 获取同步进度

```bash
GET /api/admin/progress
```

> 实际进度请直接查询 ClickHouse：
> ```sql
> SELECT * FROM archive_progress FINAL ORDER BY table_name
> ```

## 切换同步节点

如果需要切换同步节点到另一台机器：

```bash
# 1. 停止旧同步节点
# 注意：/api/admin/stop 接口未实现，直接停止进程即可

# 2. 检查进度
clickhouse-client -q "SELECT * FROM archive_progress FINAL ORDER BY table_name"

# 3. 启动新同步节点
java -jar wf-message-archive-server.jar \
  --server.port=8080 \
  --archive.task.enabled=true
```

## 配置说明

### 核心配置

```properties
# ========== 节点类型配置 ==========
archive.task.enabled=false

# ========== 同步任务配置（仅同步节点有效）==========
# 批次大小
archive.task.batch-size=500

# 批次间隔（毫秒）
archive.task.batch-interval-ms=1000

# 优雅停机超时（秒）
archive.task.shutdown-timeout-sec=30

# 安静时间开始（HH:mm）
archive.task.quiet-time-start=02:00

# 安静时间结束（HH:mm）
archive.task.quiet-time-end=06:00

# 每轮处理的批次数
archive.task.batches-per-cycle=10

# 同步历史消息的天数（0表示全部）
archive.task.sync-history-days=365

# ========== ClickHouse 配置 ==========
archive.clickhouse.url=jdbc:clickhouse://localhost:8123/default
archive.clickhouse.username=default
archive.clickhouse.password=
archive.clickhouse.table=message_archive
archive.clickhouse.max-batch-size=5000

# ========== MySQL 源配置 ==========
archive.mysql.url=jdbc:mysql://localhost:3306/im_db
archive.mysql.username=root
archive.mysql.password=password

# ========== MongoDB 源配置 ==========
archive.mongodb.uri=mongodb://localhost:27017/wfchat
archive.mongodb.database=wfchat
```

### 性能调优

```properties
# 增加批处理大小（如果内存充足）
archive.task.batch-size=1000

# 减少批次间隔（加快同步速度）
archive.task.batch-interval-ms=500

# 增加每轮批次数（安静时间处理更多数据）
archive.task.batches-per-cycle=20
```

## 监控告警

### 关键指标

| 指标 | 采集方式 | 告警阈值 |
|------|----------|----------|
| 服务存活 | `/api/health/ping` | 连续失败3次 |
| ClickHouse连接 | `/api/health/check` | 连接失败 |
| 同步任务运行 | `/api/health/detail` | running=false 持续>5分钟 |
| 处理延迟 | `/api/health/detail` | lagMs > 5分钟 |
| 错误率 | `/api/health/detail` | totalFailed 增长过快 |
| 缺失消息数 | `/api/admin/status` | missingMessageCount 持续增长 |

### ClickHouse 查询

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

-- 查看消息内容缺失统计（用于监控）
SELECT 
    count() as archived_count,
    countIf(searchable_key = '') as empty_content_count
FROM message_archive FINAL;
```

## 故障排查

### 问题1：数据重复

**原因：** 启动了多个同步节点

**解决：** 
1. 停止所有同步节点
2. 清理重复数据（使用 ReplacingMergeTree 的 FINAL 关键字查询）
3. 只启动一个同步节点

### 问题2：同步进度停滞

**排查：**
1. 检查同步节点状态：`GET /api/admin/status`
2. 查看同步节点日志是否有错误
3. 检查源数据库连接
4. 检查 ClickHouse 写入是否正常
5. 检查是否在安静时间之外运行

### 问题3：如何确认只运行了一个同步节点

```bash
# 查看日志中的启动警告
grep "WARNING: This is a SYNC node" logs/*.log | wc -l
# 应该只有 1 条
```

### 问题4：搜索无结果或结果不准确

**排查：**
1. 检查 `searchable_key` 字段是否有数据
2. n-gram 索引要求搜索词长度 >= 3 个字符
3. 查看日志中的假阳性过滤统计
4. 执行 `OPTIMIZE TABLE message_archive FINAL` 重建索引

## 数据安全

| 场景 | 机制 | 结果 |
|------|------|------|
| 正常同步 | 顺序读取，批量写入 | 正常归档 |
| 同步节点重启 | 从 last_id 继续 | 无遗漏 |
| 幂等写入 | ReplacingMergeTree 引擎 | 重复数据自动去重 |
| 批量失败 | 不更新进度，下次重试 | 数据不丢失 |
| 优雅停机 | 等待当前批次完成 | 数据完整性保证 |
| 消息缺失 | 记录缺失消息ID到日志 | 便于审计和排查 |

## 开发

### 构建

```bash
mvn clean package -DskipTests
```

### 运行测试

```bash
mvn test
```

## 许可证

Apache License 2.0
