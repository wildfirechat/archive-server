package cn.wildfirechat.archive.metrics;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 归档服务性能指标监控（简化版）
 */
@Slf4j
@Component
public class ArchiveMetrics {
    
    private final LongAdder totalMessagesProcessed = new LongAdder();
    private final LongAdder totalMessagesFailed = new LongAdder();
    private final LongAdder totalBatchesProcessed = new LongAdder();
    
    private final AtomicLong currentProcessingLagMs = new AtomicLong(0);
    private final AtomicLong maxProcessingLagMs = new AtomicLong(0);
    
    private final AtomicLong lastReportTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastProcessedCount = new AtomicLong(0);
    
    /**
     * 记录成功处理的消息
     */
    public void recordMessagesProcessed(int count, long batchDurationMs) {
        totalMessagesProcessed.add(count);
        totalBatchesProcessed.increment();
    }
    
    /**
     * 记录处理失败
     */
    public void recordMessagesFailed(int count) {
        totalMessagesFailed.add(count);
    }
    
    /**
     * 记录处理延迟
     */
    public void recordProcessingLag(long lagMs) {
        currentProcessingLagMs.set(lagMs);
        long currentMax;
        while ((currentMax = maxProcessingLagMs.get()) < lagMs) {
            if (maxProcessingLagMs.compareAndSet(currentMax, lagMs)) {
                break;
            }
        }
    }
    
    /**
     * 生成统计报告
     */
    public String generateReport() {
        long now = System.currentTimeMillis();
        long lastReport = lastReportTime.getAndSet(now);
        long timeDeltaSec = Math.max(1, (now - lastReport) / 1000);
        
        long currentTotal = totalMessagesProcessed.sum();
        long lastCount = lastProcessedCount.getAndSet(currentTotal);
        long messagesPerSec = (currentTotal - lastCount) / timeDeltaSec;
        
        long totalProcessed = totalMessagesProcessed.sum();
        long totalFailed = totalMessagesFailed.sum();
        double errorRate = totalProcessed > 0 ? (double) totalFailed / totalProcessed * 100 : 0;
        
        StringBuilder report = new StringBuilder();
        report.append("\n========== Archive Metrics Report ==========\n");
        report.append(String.format("Node Type: %s\n", 
            totalBatchesProcessed.sum() > 0 ? "SYNC" : "QUERY"));
        report.append(String.format("Messages Processed: %,d (%.1f msg/sec)\n", 
            totalProcessed, (double) messagesPerSec));
        report.append(String.format("Messages Failed: %,d (%.2f%%)\n", 
            totalFailed, errorRate));
        report.append(String.format("Current Lag: %,d ms (Max: %,d ms)\n", 
            currentProcessingLagMs.get(), maxProcessingLagMs.get()));
        report.append("============================================\n");
        
        return report.toString();
    }
    
    /**
     * 获取状态摘要
     */
    public StatusSummary getStatusSummary() {
        return new StatusSummary(
            totalMessagesProcessed.sum(),
            totalMessagesFailed.sum(),
            currentProcessingLagMs.get()
        );
    }
    
    @Getter
    public static class StatusSummary {
        private final long totalProcessed;
        private final long totalFailed;
        private final long currentLagMs;
        
        public StatusSummary(long totalProcessed, long totalFailed, long currentLagMs) {
            this.totalProcessed = totalProcessed;
            this.totalFailed = totalFailed;
            this.currentLagMs = currentLagMs;
        }
        
        public boolean isHealthy() {
            return currentLagMs < 300_000;  // 5分钟
        }
    }
}
