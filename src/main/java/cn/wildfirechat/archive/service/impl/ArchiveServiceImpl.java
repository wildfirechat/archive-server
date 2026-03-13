package cn.wildfirechat.archive.service.impl;

import cn.wildfirechat.archive.config.ArchiveProperties;
import cn.wildfirechat.archive.entity.ArchivedGroupMessage;
import cn.wildfirechat.archive.entity.ArchivedMessage;
import cn.wildfirechat.archive.entity.GroupMessage;
import cn.wildfirechat.archive.entity.Message;
import cn.wildfirechat.archive.entity.UserMessage;
import cn.wildfirechat.archive.metrics.ArchiveMetrics;
import cn.wildfirechat.archive.repository.clickhouse.ArchiveRepository;
import cn.wildfirechat.archive.repository.source.MessageSource;
import cn.wildfirechat.archive.service.ArchiveService;
import cn.wildfirechat.archive.service.MessageIdUtil;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 消息归档服务
 * 
 * 部署模式：
 * 1. 单同步节点 + 多查询节点
 * 2. 通过配置 archive.task.enabled 区分节点类型
 * 
 * ⚠️ 重要：只能启动一个 enabled=true 的节点！
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchiveServiceImpl implements ArchiveService {
    
    private final MessageSource messageSource;
    private final ArchiveRepository archiveRepository;
    private final MessageIdUtil messageIdUtil;
    private final ArchiveProperties archiveProperties;
    private final ArchiveMetrics metrics;
    
    private final String nodeId = generateNodeId();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    
    private ExecutorService archiveExecutor;
    private ScheduledExecutorService metricsExecutor;
    
    // 停机协调
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private volatile boolean archiveTaskActive = false;
    
    // 消息缓存，返回的 Message 中 mid 为 -1 表示消息不存在（防缓存穿透）
    private LoadingCache<Long, Message> messageCache;
    
    // 缺失消息统计（用于监控）
    private final LongAdder missingMessageCount = new LongAdder();
    private final Set<Long> recentMissingMids = ConcurrentHashMap.newKeySet();
    
    // 群组消息缺失统计
    private final LongAdder missingGroupMessageCount = new LongAdder();
    private final Set<Long> recentMissingGroupMids = ConcurrentHashMap.newKeySet();
    
    // 重试配置
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;
    
    @PostConstruct
    public void init() {
        // 初始化缓存（所有节点都需要，因为查询时可能用到）
        messageCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build(this::loadMessageFromSource);
        
        log.info("Archive service initializing... Task enabled: {}", archiveProperties.getTask().isEnabled());
        
        if (archiveProperties.getTask().isEnabled()) {
            // ⚠️ 警告：这是同步节点，只能启动一个！
            log.warn("╔════════════════════════════════════════════════════════════╗");
            log.warn("║  ⚠️  WARNING: This is a SYNC node!                        ║");
            log.warn("║     Only ONE sync node should be running!                 ║");
            log.warn("║     Node ID: {}", nodeId + "                    ║".substring(Math.min(nodeId.length(), 20)));
            
            int syncHistoryDays = archiveProperties.getTask().getSyncHistoryDays();
            if (syncHistoryDays > 0) {
                log.warn("║     Sync Range: {} days before today                    ║", syncHistoryDays);
            } else {
                log.warn("║     Sync Range: ALL messages                            ║");
            }
            
            log.warn("╚════════════════════════════════════════════════════════════╝");
            
            startArchiveTask();
            startMetricsReporting();
            
            // 注册 JVM 停机钩子
            Runtime.getRuntime().addShutdownHook(new Thread(this::emergencyShutdown));
        } else {
            log.info("Archive service initialized as QUERY node: {}", nodeId);
        }
    }
    
    @PreDestroy
    public void destroy() {
        if (archiveProperties.getTask().isEnabled()) {
            gracefulShutdown();
        }
    }
    
    /**
     * 优雅停机
     */
    private void gracefulShutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        
        log.info("Starting graceful shutdown on sync node: {}...", nodeId);
        
        // 等待当前批次完成
        if (archiveTaskActive) {
            log.info("Waiting for current archive batch to complete...");
            try {
                shutdownLatch.await(archiveProperties.getTask().getShutdownTimeoutSec(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Shutdown wait interrupted");
            }
        }
        
        stopMetricsReporting();
        stopArchiveTask();
        
        // 打印最终统计
        log.info(metrics.generateReport());
        
        log.info("Graceful shutdown completed on node: {}", nodeId);
    }
    
    /**
     * 紧急停机
     */
    private void emergencyShutdown() {
        if (!shuttingDown.get()) {
            log.warn("Emergency shutdown triggered for node: {}", nodeId);
            running.set(false);
        }
    }
    
    private String generateNodeId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (UnknownHostException e) {
            return "node-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
    
    /**
     * 启动归档任务（仅同步节点）
     */
    private void startArchiveTask() {
        log.info("Starting archive task...");
        
        archiveExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "archive-task");
            t.setDaemon(true);
            return t;
        });
        
        running.set(true);
        
        archiveExecutor.submit(() -> {
            log.info("Archive sync task started on node: {}", nodeId);
            
            try {
                while (running.get() && !shuttingDown.get()) {
                    archiveTaskActive = true;
                    
                    try {
                        boolean hasMore = doArchiveCycle();
                        
                        if (!hasMore && !shuttingDown.get()) {
                            log.debug("All tables processed, resting...");
                            Thread.sleep(60000);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("Error in archive task", e);
                        metrics.recordMessagesFailed(0);
                        try { 
                            Thread.sleep(30000); 
                        } catch (InterruptedException ie) { 
                            Thread.currentThread().interrupt(); 
                            break; 
                        }
                    }
                }
            } finally {
                archiveTaskActive = false;
                shutdownLatch.countDown();
                if (running.get()) {
                    running.set(false);
                }
                log.info("Archive task stopped on node: {}", nodeId);
            }
        });
    }
    
    private void stopArchiveTask() {
        running.set(false);
        if (archiveExecutor != null) {
            archiveExecutor.shutdown();
            try {
                archiveExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                archiveExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * 启动指标报告
     */
    private void startMetricsReporting() {
        metricsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-reporter");
            t.setDaemon(true);
            return t;
        });
        
        metricsExecutor.scheduleAtFixedRate(() -> {
            log.info(metrics.generateReport());
            // 定期清理缺失消息记录，避免内存泄漏
            long missingCount = missingMessageCount.sum();
            if (missingCount > 0) {
                log.warn("Missing messages detected: {}, recent MIDs: {}", missingCount, recentMissingMids.size());
                if (recentMissingMids.size() > 1000) {
                    recentMissingMids.clear();
                }
            }
        }, 5, 5, TimeUnit.MINUTES);
    }
    
    private void stopMetricsReporting() {
        if (metricsExecutor != null) {
            metricsExecutor.shutdown();
            try {
                metricsExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                metricsExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * 执行一次同步轮次
     * 
     * 安静时间策略：
     * - 安静时间（如 02:00-06:00）：高频同步，处理多个批次
     * - 非安静时间：低频同步，减少系统负载
     */
    private boolean doArchiveCycle() {
        boolean anyHasMore = false;
        int batchSize = archiveProperties.getTask().getBatchSize();
        int batchIntervalMs = archiveProperties.getTask().getBatchIntervalMs();
        int batchesPerCycle = archiveProperties.getTask().getBatchesPerCycle();
        int syncHistoryDays = archiveProperties.getTask().getSyncHistoryDays();
        boolean syncGroupEnabled = archiveProperties.getTask().isSyncGroupEnabled();
        
        boolean isQuietTime = isQuietTime();

        if(!isQuietTime) {
            log.debug("It's not quiet time, skip current cycle.");
            return false;
        }
        
        if (syncHistoryDays > 0) {
            log.info("Starting archive cycle (sync messages older than {} days, quiet time: {}, group sync: {})...", 
                syncHistoryDays, isQuietTime, syncGroupEnabled);
        } else {
            log.info("Starting archive cycle (sync all messages, quiet time: {}, group sync: {})...", 
                isQuietTime, syncGroupEnabled);
        }

        int cyclesToRun = batchesPerCycle;
        long cycleIntervalMs = batchIntervalMs;
        
        for (int cycle = 0; cycle < cyclesToRun; cycle++) {
            if (shuttingDown.get()) {
                log.info("Shutting down, stopping cycle {} of {}", cycle + 1, cyclesToRun);
                break;
            }
            
            if (cyclesToRun > 1) {
                log.debug("Running batch {} of {}", cycle + 1, cyclesToRun);
            }
            
            // 1. 同步普通消息（用户消息表）
            for (int tableIndex = 0; tableIndex < 128; tableIndex++) {
                if (shuttingDown.get()) {
                    log.info("Shutting down, stopping cycle at table {}", tableIndex);
                    break;
                }

                try {
                    log.debug("Waiting {} ms before next batch...", cycleIntervalMs);
                    Thread.sleep(cycleIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (shuttingDown.get()) {
                    log.info("Shutting down, stopping cycle at table {}", tableIndex);
                    break;
                }
                
                String tableName = "t_user_messages_" + tableIndex;
                log.debug("Processing table: {}", tableName);
                
                try {
                    BatchResult result = doArchiveBatchWithRetry(tableName, tableIndex, batchSize);
                    if (result.processedCount > 0) {
                        log.info("Table {} processed {} messages", tableName, result.processedCount);
                    }
                    if (result.hasMore) {
                        anyHasMore = true;
                    }
                } catch (Exception e) {
                    log.error("Error processing table {}: {}", tableName, e.getMessage());
                }
            }
            
            // 2. 同步群组消息（如果启用）
            if (syncGroupEnabled) {
                for (int tableIndex = 0; tableIndex < 128; tableIndex++) {
                    if (shuttingDown.get()) {
                        log.info("Shutting down, stopping group cycle at table {}", tableIndex);
                        break;
                    }

                    try {
                        log.debug("Waiting {} ms before next group batch...", cycleIntervalMs);
                        Thread.sleep(cycleIntervalMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    if (shuttingDown.get()) {
                        log.info("Shutting down, stopping group cycle at table {}", tableIndex);
                        break;
                    }
                    
                    String tableName = "t_group_messages_" + tableIndex;
                    log.debug("Processing group table: {}", tableName);
                    
                    try {
                        GroupBatchResult result = doGroupArchiveBatchWithRetry(tableName, tableIndex, batchSize);
                        if (result.processedCount > 0) {
                            log.info("Group table {} processed {} messages", tableName, result.processedCount);
                        }
                        if (result.hasMore) {
                            anyHasMore = true;
                        }
                    } catch (Exception e) {
                        log.error("Error processing group table {}: {}", tableName, e.getMessage());
                    }
                }
            }
        }
        
        log.info("Archive cycle completed. Has more data: {}", anyHasMore);
        return anyHasMore;
    }
    
    /**
     * 检查当前是否在安静时间
     * 
     * 注意：安静时间使用 JVM 默认时区（服务器本地时区）
     * 配置中的时间应该按照服务器本地时间填写
     * 例如：服务器在中国，配置 "02:00" 表示北京时间凌晨2点
     */
    private boolean isQuietTime() {
        String quietTimeStart = archiveProperties.getTask().getQuietTimeStart();
        String quietTimeEnd = archiveProperties.getTask().getQuietTimeEnd();
        
        if (quietTimeStart == null || quietTimeEnd == null) {
            return false;
        }
        
        try {
            // 安静时间使用系统默认时区（本地时区）
            LocalTime now = LocalTime.now(ZoneId.systemDefault());
            LocalTime start = LocalTime.parse(quietTimeStart);
            LocalTime end = LocalTime.parse(quietTimeEnd);
            
            if (start.isBefore(end)) {
                // 普通情况：如 02:00-06:00
                return !now.isBefore(start) && now.isBefore(end);
            } else {
                // 跨午夜：如 22:00-06:00
                return !now.isBefore(start) || now.isBefore(end);
            }
        } catch (Exception e) {
            log.warn("Failed to parse quiet time config: {} - {}, error: {}", 
                quietTimeStart, quietTimeEnd, e.getMessage());
            return false;
        }
    }
    
    private static class BatchResult {
        final boolean hasMore;
        final int processedCount;
        final int missingCount;  // 新增：记录缺失消息数
        
        BatchResult(boolean hasMore, int processedCount, int missingCount) {
            this.hasMore = hasMore;
            this.processedCount = processedCount;
            this.missingCount = missingCount;
        }
    }
    
    private static class GroupBatchResult {
        final boolean hasMore;
        final int processedCount;
        final int missingCount;
        
        GroupBatchResult(boolean hasMore, int processedCount, int missingCount) {
            this.hasMore = hasMore;
            this.processedCount = processedCount;
            this.missingCount = missingCount;
        }
    }
    
    /**
     * 获取最晚允许同步的消息时间（毫秒时间戳）
     * 
     * 策略：
     * 1. 当 syncHistoryDays > 0 时：只同步 N 天前的消息（旧消息）
     * 2. 当 syncHistoryDays = 0 时：只同步 5 分钟之前的消息
     * 
     * 5 分钟延迟的原因：
     * 数据源的消息写入是批量写入的，可能先写入 user_message 后写入 message，
     * 为避免同步时找不到对应的消息内容，需要延迟 5 分钟同步，确保数据一致性
     */
    private static final long SYNC_DELAY_MS = 5 * 60 * 1000L; // 5 分钟延迟
    
    private long getMaxMessageTimestamp() {
        int days = archiveProperties.getTask().getSyncHistoryDays();
        long baseTimestamp = System.currentTimeMillis() - SYNC_DELAY_MS;
        
        if (days <= 0) {
            // syncHistoryDays = 0：只同步 5 分钟之前的消息
            return baseTimestamp;
        }
        
        // syncHistoryDays > 0：同步 N 天前且至少 5 分钟之前的消息
        long historyTimestamp = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);
        return Math.min(historyTimestamp, baseTimestamp);
    }
    
    /**
     * 带重试的批次处理
     */
    private BatchResult doArchiveBatchWithRetry(String tableName, int tableIndex, int batchSize) {
        int retryCount = 0;
        Exception lastException = null;
        
        while (retryCount < MAX_RETRIES) {
            try {
                return doArchiveBatch(tableName, tableIndex, batchSize);
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                
                // 判断是否为可重试错误
                if (isRetryableException(e) && retryCount < MAX_RETRIES) {
                    log.warn("Batch processing failed for table {}, retrying {}/{}: {}", 
                        tableName, retryCount, MAX_RETRIES, e.getMessage());
                    try {
                        Thread.sleep(RETRY_DELAY_MS * retryCount);  // 指数退避
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    break;  // 不可重试错误或已达到最大重试次数
                }
            }
        }
        
        // 所有重试都失败了
        log.error("Batch processing failed for table {} after {} retries: {}", 
            tableName, MAX_RETRIES, lastException != null ? lastException.getMessage() : "Unknown error");
        metrics.recordMessagesFailed(batchSize);
        return new BatchResult(false, 0, 0);
    }
    
    /**
     * 判断异常是否可重试
     */
    private boolean isRetryableException(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        // 网络相关错误、连接超时、服务暂时不可用等可重试
        String lowerMsg = message.toLowerCase();
        return lowerMsg.contains("connection") 
            || lowerMsg.contains("timeout")
            || lowerMsg.contains("unavailable")
            || lowerMsg.contains("temporarily")
            || lowerMsg.contains("try again")
            || lowerMsg.contains("deadlock")
            || lowerMsg.contains("lock wait timeout");
    }
    
    /**
     * 处理单个表的一个批次
     */
    private BatchResult doArchiveBatch(String tableName, int tableIndex, int batchSize) throws Exception {
        long startTime = System.currentTimeMillis();
        int processedCount = 0;
        int missingCount = 0;
        
        // 获取最后同步的mid
        Long lastMid = getLastMid(tableName);
        
        // 获取最晚允许同步的时间（N天前）
        // 使用 UTC 时间，与 messageDt 存储时区保持一致
        long maxTimestamp = getMaxMessageTimestamp();
        LocalDateTime endTime = maxTimestamp < Long.MAX_VALUE
            ? LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(maxTimestamp), ZoneId.of("UTC"))
            : null;
        if (maxTimestamp < Long.MAX_VALUE) {
            log.debug("Sync history limit: {} days before, endTime(UTC): {}",
                archiveProperties.getTask().getSyncHistoryDays(), endTime);
        }

        // 读取用户消息（带时间筛选）
        List<UserMessage> userMessages = messageSource.fetchUserMessagesByMidRange(tableIndex, lastMid, batchSize, null, endTime);
        
        if (userMessages.isEmpty()) {
            return new BatchResult(false, 0, 0);
        }
        
        // 批量查询消息内容（避免逐个查询）
        List<Long> mids = userMessages.stream()
            .map(UserMessage::getMid)
            .distinct()
            .collect(java.util.stream.Collectors.toList());
        
        Map<Long, Message> messageMap = fetchMessagesWithRetry(mids);
        
        // 合并为宽表记录，同时记录缺失的消息
        List<ArchivedMessage> archivedMessages = new ArrayList<>();
        for (UserMessage um : userMessages) {
            Message msg = messageMap.get(um.getMid());
            //msg id为null时是有效的，-1为无效的
            if (msg != null && msg.getId() == null) {
                archivedMessages.add(convertToArchivedMessage(msg, um));
            } else {
                missingCount++;
                recordMissingMessage(um.getMid());
            }
        }
        
        // 写入ClickHouse - 确保写入成功后再更新进度
        boolean writeSuccess = false;
        if (!archivedMessages.isEmpty()) {
            try {
                archiveRepository.batchInsertMessages(archivedMessages);
                writeSuccess = true;
                
                // 记录延迟
                try {
                    ArchivedMessage lastMsg = archivedMessages.get(archivedMessages.size() - 1);
                    // 使用 UTC 计算延迟（messageDt 存储的是 UTC 时间）
                    long msgTime = lastMsg.getMessageDt()
                        .atZone(ZoneId.of("UTC"))
                        .toInstant()
                        .toEpochMilli();
                    long lag = System.currentTimeMillis() - msgTime;
                    if (lag >= 0) {
                        metrics.recordProcessingLag(lag);
                    }
                } catch (Exception e) {
                    log.debug("Failed to record processing lag: {}", e.getMessage());
                }
            } catch (Exception e) {
                log.error("Failed to write batch to ClickHouse: {}", e.getMessage());
                throw e;  // 抛出异常，让上层重试
            }
        } else {
            // 如果没有消息需要写入，也认为成功（可能是所有消息都缺失）
            writeSuccess = true;
        }
        
        // 只有在写入成功后才更新进度
        if (writeSuccess) {
            UserMessage last = userMessages.get(userMessages.size() - 1);
            updateProgress(tableName, last.getMid());
        }
        
        processedCount = archivedMessages.size();
        
        // 记录缺失消息
        if (missingCount > 0) {
            missingMessageCount.add(missingCount);
            log.warn("Table {} has {} messages with missing content (out of {})", 
                tableName, missingCount, userMessages.size());
        }
        
        return new BatchResult(userMessages.size() >= batchSize, processedCount, missingCount);
    }
    
    /**
     * 批量获取消息内容，带重试机制
     * 注意：返回的 Map 中，如果消息不存在，不会包含该 mid（即跳过空消息）
     */
    private Map<Long, Message> fetchMessagesWithRetry(List<Long> mids) throws Exception {
        Map<Long, Message> result = new HashMap<>();
        List<Long> pendingMids = new ArrayList<>(mids);
        
        int retryCount = 0;
        while (!pendingMids.isEmpty() && retryCount < MAX_RETRIES) {
            Map<Long, Message> batchResult = new HashMap<>();
            
            // 批量查询，而不是逐个查询
            for (Long mid : pendingMids) {
                Message msg = messageCache.get(mid);
                if (msg != null) {
                    batchResult.put(mid, msg);
                }
            }
            
            result.putAll(batchResult);
            
            // 检查还有哪些消息未获取到（不包括已确认不存在的消息）
            pendingMids.removeAll(batchResult.keySet());
            
            if (!pendingMids.isEmpty() && retryCount < MAX_RETRIES - 1) {
                retryCount++;
                log.debug("{} messages not found, retrying {}/{}", pendingMids.size(), retryCount, MAX_RETRIES);
                Thread.sleep(RETRY_DELAY_MS * retryCount);
                // 清除缓存，强制重新加载
                pendingMids.forEach(messageCache::invalidate);
            } else {
                break;
            }
        }
        
        return result;
    }
    
    /**
     * 记录缺失的消息ID（用于监控和审计）
     */
    private void recordMissingMessage(Long mid) {
        recentMissingMids.add(mid);
        if (recentMissingMids.size() > 10000) {
            // 防止内存无限增长，保留最近的10000条
            Iterator<Long> it = recentMissingMids.iterator();
            for (int i = 0; i < 1000 && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }
    
    private Long getLastMid(String tableName) {
        String sql = "SELECT last_mid FROM archive_progress FINAL WHERE table_name = ?";
        try {
            return archiveRepository.queryForObject(sql, Long.class, tableName);
        } catch (Exception e) {
            return 0L;
        }
    }
    
    private void updateProgress(String tableName, long lastMid) {
        String sql = "INSERT INTO archive_progress (table_name, last_mid, updated_at) VALUES (?, ?, ?)";
        try {
            // 使用 UTC 时间存储，统一时间标准
            archiveRepository.update(sql, tableName, lastMid, Timestamp.valueOf(LocalDateTime.now(ZoneId.of("UTC"))));
        } catch (Exception e) {
            log.error("Failed to update progress for {}: {}", tableName, e.getMessage());
            throw new RuntimeException("Failed to update progress", e);  // 抛出异常，让上层重试
        }
    }
    
    /**
     * 计算userHash，避免Integer.MIN_VALUE的abs问题
     */
    private int calculateUserHash(String userId) {
        if (userId == null) {
            return 0;
        }
        int hash = userId.hashCode() % 128;
        // 修复：Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE (负数)
        return Math.abs(hash) % 128;
    }
    
    private ArchivedMessage convertToArchivedMessage(Message msg, UserMessage um) {
        ArchivedMessage am = new ArchivedMessage();
        am.setMid(um.getMid());
        am.setSenderId(msg.getFrom());
        am.setConvType(um.getType());
        am.setConvTarget(um.getTarget());
        am.setConvLine(um.getLine());
        am.setContentType(um.getContType());
        am.setContentData(msg.getData() != null ? Base64.getEncoder().encodeToString(msg.getData()) : null);
        am.setSearchableKey(msg.getSearchableKey() != null ? msg.getSearchableKey() : "");
        am.setUserId(um.getUid());
        am.setMessageDt(msg.getDt());
        am.setUserHash(calculateUserHash(um.getUid()));  // 使用修复后的方法
        return am;
    }
    
    /**
     * 从源加载消息
     * 如果消息不存在，返回一个 mid 为 -1 的 Message 对象（防缓存穿透）
     * 
     * @param mid 消息 ID
     * @return 消息对象，不存在时返回 mid 为 -1 的空对象
     */
    private Message loadMessageFromSource(Long mid) {
        if (mid == null || mid <= 0) {
            // 返回空消息对象，防止缓存穿透
            return createEmptyMessage();
        }
        try {
            int tableIndex = messageIdUtil.getTableIndex(mid);
            Message msg = messageSource.fetchMessagesByMids(tableIndex, mid);
            if (msg == null) {
                // 消息不存在，返回空消息对象，防止缓存穿透
                log.debug("Message {} not found in source, creating empty placeholder", mid);
                return createEmptyMessage();
            }
            return msg;
        } catch (Exception e) {
            log.warn("Failed to load message {} from source: {}", mid, e.getMessage());
            // 发生异常时也返回空消息对象，防止缓存穿透
            return createEmptyMessage();
        }
    }
    
    /**
     * 创建空消息对象（用于缓存穿透防护）
     * mid 为 -1 表示消息不存在
     */
    private Message createEmptyMessage() {
        Message emptyMsg = new Message();
        emptyMsg.setMid(-1L);
        return emptyMsg;
    }
    
    @Override
    public boolean isRunning() {
        return running.get();
    }
    
    public String getNodeId() {
        return nodeId;
    }
    
    /**
     * 获取当前状态
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("nodeId", nodeId);
        status.put("nodeType", archiveProperties.getTask().isEnabled() ? "SYNC" : "QUERY");
        status.put("running", running.get());
        status.put("shuttingDown", shuttingDown.get());
        status.put("metrics", metrics.getStatusSummary());
        status.put("missingMessageCount", missingMessageCount.sum());
        status.put("recentMissingMids", new ArrayList<>(recentMissingMids));
        status.put("missingGroupMessageCount", missingGroupMessageCount.sum());
        status.put("recentMissingGroupMids", new ArrayList<>(recentMissingGroupMids));
        status.put("syncGroupEnabled", archiveProperties.getTask().isSyncGroupEnabled());
        return status;
    }
    
    // ==================== 群组消息同步相关方法 ====================
    
    /**
     * 带重试的群组消息批次处理
     */
    private GroupBatchResult doGroupArchiveBatchWithRetry(String tableName, int tableIndex, int batchSize) {
        int retryCount = 0;
        Exception lastException = null;
        
        while (retryCount < MAX_RETRIES) {
            try {
                return doGroupArchiveBatch(tableName, tableIndex, batchSize);
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                
                if (isRetryableException(e) && retryCount < MAX_RETRIES) {
                    log.warn("Group batch processing failed for table {}, retrying {}/{}: {}", 
                        tableName, retryCount, MAX_RETRIES, e.getMessage());
                    try {
                        Thread.sleep(RETRY_DELAY_MS * retryCount);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        
        log.error("Group batch processing failed for table {} after {} retries: {}", 
            tableName, MAX_RETRIES, lastException != null ? lastException.getMessage() : "Unknown error");
        metrics.recordMessagesFailed(batchSize);
        return new GroupBatchResult(false, 0, 0);
    }
    
    /**
     * 处理单个群组消息表的一个批次
     */
    private GroupBatchResult doGroupArchiveBatch(String tableName, int tableIndex, int batchSize) throws Exception {
        int processedCount = 0;
        int missingCount = 0;
        
        // 获取最后同步的mid
        Long lastMid = getLastMid(tableName);
        
        // 获取最晚允许同步的时间
        long maxTimestamp = getMaxMessageTimestamp();
        LocalDateTime endTime = maxTimestamp < Long.MAX_VALUE 
            ? LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(maxTimestamp), ZoneId.of("UTC"))
            : null;
        
        // 读取群组消息
        List<GroupMessage> groupMessages = messageSource.fetchGroupMessagesByMidRange(tableIndex, lastMid, batchSize, 0, maxTimestamp);
        
        if (groupMessages.isEmpty()) {
            return new GroupBatchResult(false, 0, 0);
        }
        
        // 批量查询消息内容
        List<Long> mids = groupMessages.stream()
            .map(GroupMessage::getMid)
            .distinct()
            .collect(java.util.stream.Collectors.toList());
        
        Map<Long, Message> messageMap = fetchMessagesWithRetry(mids);
        
        // 合并为宽表记录
        List<ArchivedGroupMessage> archivedMessages = new ArrayList<>();
        for (GroupMessage gm : groupMessages) {
            Message msg = messageMap.get(gm.getMid());
            // msg id为null时是有效的，-1为无效的
            if (msg != null && msg.getId() == null) {
                archivedMessages.add(convertToArchivedGroupMessage(msg, gm));
            } else {
                missingCount++;
                recordMissingGroupMessage(gm.getMid());
            }
        }
        
        // 写入 ClickHouse
        boolean writeSuccess = false;
        if (!archivedMessages.isEmpty()) {
            try {
                archiveRepository.batchInsertGroupMessages(archivedMessages);
                writeSuccess = true;
                
                // 记录延迟
                try {
                    ArchivedGroupMessage lastMsg = archivedMessages.get(archivedMessages.size() - 1);
                    long msgTime = lastMsg.getMessageDt()
                        .atZone(ZoneId.of("UTC"))
                        .toInstant()
                        .toEpochMilli();
                    long lag = System.currentTimeMillis() - msgTime;
                    if (lag >= 0) {
                        metrics.recordProcessingLag(lag);
                    }
                } catch (Exception e) {
                    log.debug("Failed to record group processing lag: {}", e.getMessage());
                }
            } catch (Exception e) {
                log.error("Failed to write group batch to ClickHouse: {}", e.getMessage());
                throw e;
            }
        } else {
            writeSuccess = true;
        }
        
        // 更新进度
        if (writeSuccess) {
            GroupMessage last = groupMessages.get(groupMessages.size() - 1);
            updateProgress(tableName, last.getMid());
        }
        
        processedCount = archivedMessages.size();
        
        // 记录缺失消息
        if (missingCount > 0) {
            missingGroupMessageCount.add(missingCount);
            log.warn("Group table {} has {} messages with missing content (out of {})", 
                tableName, missingCount, groupMessages.size());
        }
        
        return new GroupBatchResult(groupMessages.size() >= batchSize, processedCount, missingCount);
    }
    
    /**
     * 记录缺失的群组消息ID
     */
    private void recordMissingGroupMessage(Long mid) {
        recentMissingGroupMids.add(mid);
        if (recentMissingGroupMids.size() > 10000) {
            Iterator<Long> it = recentMissingGroupMids.iterator();
            for (int i = 0; i < 1000 && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }
    
    /**
     * 计算 groupHash，避免 Integer.MIN_VALUE 的 abs 问题
     */
    private int calculateGroupHash(String gid) {
        if (gid == null) {
            return 0;
        }
        int hash = gid.hashCode() % 128;
        return Math.abs(hash) % 128;
    }
    
    /**
     * 将 GroupMessage 和 Message 合并为 ArchivedGroupMessage
     */
    private ArchivedGroupMessage convertToArchivedGroupMessage(Message msg, GroupMessage gm) {
        ArchivedGroupMessage am = new ArchivedGroupMessage();
        am.setMid(gm.getMid());
        am.setSenderId(gm.getSender());
        am.setGid(gm.getGid());
        am.setConvLine(gm.getLine());
        am.setContentType(gm.getContType());
        am.setContentData(msg.getData() != null ? 
            Base64.getEncoder().encodeToString(msg.getData()) : null);
        am.setSearchableKey(msg.getSearchableKey() != null ? msg.getSearchableKey() : "");
        // 将毫秒时间戳转为 LocalDateTime
        am.setMessageDt(msg.getDt());
        am.setGroupHash(calculateGroupHash(gm.getGid()));
        return am;
    }
}
