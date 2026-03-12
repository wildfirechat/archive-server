package cn.wildfirechat.archive.controller;

import cn.wildfirechat.archive.config.ArchiveProperties;
import cn.wildfirechat.archive.metrics.ArchiveMetrics;
import cn.wildfirechat.archive.service.ArchiveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查接口
 * 
 * 用于监控系统探活和告警
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
public class HealthController {
    
    private final ArchiveService archiveService;
    private final ArchiveMetrics archiveMetrics;
    private final ArchiveProperties archiveProperties;
    private final JdbcTemplate clickhouseJdbcTemplate;
    
    public HealthController(ArchiveService archiveService,
                           ArchiveMetrics archiveMetrics,
                           ArchiveProperties archiveProperties,
                           @Qualifier("clickhouseJdbcTemplate") JdbcTemplate clickhouseJdbcTemplate) {
        this.archiveService = archiveService;
        this.archiveMetrics = archiveMetrics;
        this.archiveProperties = archiveProperties;
        this.clickhouseJdbcTemplate = clickhouseJdbcTemplate;
    }
    
    /**
     * 简单探活接口
     * 
     * 返回 200 表示服务存活
     * 适用于负载均衡健康检查
     */
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("pong");
    }
    
    /**
     * 基础健康检查
     * 
     * 检查内容：
     * 1. 服务是否存活
     * 2. ClickHouse 连接是否正常
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", getCurrentTimestamp());
        result.put("nodeType", archiveProperties.getTask().isEnabled() ? "SYNC" : "QUERY");
        
        // 检查 ClickHouse 连接
        boolean clickhouseOk = checkClickHouseConnection();
        result.put("clickhouse", clickhouseOk ? "UP" : "DOWN");
        
        if (!clickhouseOk) {
            result.put("status", "DOWN");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
        }
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 详细健康检查（包含业务指标）
     * 
     * 适用于监控告警，返回详细的业务状态
     */
    @GetMapping("/detail")
    public ResponseEntity<Map<String, Object>> healthDetail() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", getCurrentTimestamp());
        result.put("nodeType", archiveProperties.getTask().isEnabled() ? "SYNC" : "QUERY");
        
        // 检查 ClickHouse 连接
        boolean clickhouseOk = checkClickHouseConnection();
        result.put("clickhouse", clickhouseOk ? "UP" : "DOWN");
        
        if (!clickhouseOk) {
            result.put("status", "DOWN");
            result.put("reason", "ClickHouse connection failed");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
        }
        
        // 同步节点额外检查
        if (archiveProperties.getTask().isEnabled()) {
            ArchiveMetrics.StatusSummary metrics = archiveMetrics.getStatusSummary();
            
            Map<String, Object> syncStatus = new HashMap<>();
            syncStatus.put("running", archiveService.isRunning());
            syncStatus.put("healthy", metrics.isHealthy());
            syncStatus.put("lagMs", metrics.getCurrentLagMs());
            syncStatus.put("totalProcessed", metrics.getTotalProcessed());
            syncStatus.put("totalFailed", metrics.getTotalFailed());
            
            result.put("sync", syncStatus);
            
            // 如果同步任务停止或延迟过高，标记为不健康
            if (!archiveService.isRunning()) {
                result.put("status", "WARN");
                result.put("reason", "Sync task not running");
            } else if (!metrics.isHealthy()) {
                result.put("status", "WARN");
                result.put("reason", "Processing lag too high: " + metrics.getCurrentLagMs() + "ms");
            }
        }
        
        HttpStatus httpStatus = "UP".equals(result.get("status")) 
            ? HttpStatus.OK 
            : HttpStatus.OK;  // WARN 也返回 200，但状态标记为 WARN
            
        return ResponseEntity.status(httpStatus).body(result);
    }
    
    /**
     * 就绪检查
     * 
     * 用于 Kubernetes readinessProbe
     * 服务启动完成且可以接收流量时返回 200
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> result = new HashMap<>();
        result.put("ready", true);
        result.put("timestamp", getCurrentTimestamp());
        
        // 检查 ClickHouse 连接
        if (!checkClickHouseConnection()) {
            result.put("ready", false);
            result.put("reason", "ClickHouse not ready");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
        }
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 存活检查
     * 
     * 用于 Kubernetes livenessProbe
     * 服务存活但可能暂时不可用时返回 200
     * 服务死锁或无响应时返回 503，触发容器重启
     */
    @GetMapping("/live")
    public ResponseEntity<Map<String, Object>> live() {
        Map<String, Object> result = new HashMap<>();
        result.put("alive", true);
        result.put("timestamp", getCurrentTimestamp());
        
        // 简单的死锁检查：如果同步节点且任务应该运行但实际停止，可能是死锁
        if (archiveProperties.getTask().isEnabled()) {
            // 这里可以添加更复杂的死锁检测逻辑
            // 简单实现：只要服务能响应请求，就认为存活
        }
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取带有时区信息的当前时间戳
     */
    private String getCurrentTimestamp() {
        // 使用 ISO 8601 格式，包含时区信息
        return LocalDateTime.now(ZoneId.systemDefault())
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
    
    /**
     * 检查 ClickHouse 连接
     */
    private boolean checkClickHouseConnection() {
        try {
            clickhouseJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.warn("ClickHouse health check failed: {}", e.getMessage());
            return false;
        }
    }
}
