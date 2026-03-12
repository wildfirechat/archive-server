package cn.wildfirechat.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 归档服务配置属性
 * 
 * 部署说明：
 * 1. 同步节点：负责从源数据库同步消息到 ClickHouse，只能启动一个
 *    配置：archive.task.sync-enabled=true
 * 
 * 2. 查询节点：只提供消息查询 API，可以启动多个
 *    配置：archive.task.sync-enabled=false（默认）
 * 
 * ⚠️ 警告：不要启动多个同步节点，否则会导致数据重复或冲突！
 */
@Data
@Component
@ConfigurationProperties(prefix = "archive")
public class ArchiveProperties {
    
    private String sourceType = "mysql";
    
    private MysqlConfig mysql = new MysqlConfig();
    
    private MongodbConfig mongodb = new MongodbConfig();
    
    private ClickhouseConfig clickhouse = new ClickhouseConfig();
    
    private TaskConfig task = new TaskConfig();
    
    @Data
    public static class MysqlConfig {
        private String url = "jdbc:mysql://localhost:3306/im_db";
        private String username = "root";
        private String password = "password";
    }
    
    @Data
    public static class MongodbConfig {
        private String uri = "mongodb://localhost:27017/wfchat";
        private String database = "wfchat";
    }
    
    @Data
    public static class ClickhouseConfig {
        private String url = "jdbc:clickhouse://localhost:8123/default";
        private String username = "default";
        private String password = "";
        private String table = "message_archive";
        private int maxBatchSize = 5000;
    }
    
    /**
     * 任务配置
     */
    @Data
    public static class TaskConfig {
        /**
         * ⚠️ 是否启用归档任务（是否为同步节点）
         * 
         * true:  此节点为同步节点，会启动归档任务
         *        ⚠️⚠️⚠️ 只能启动一个同步节点！
         * 
         * false: 此节点为查询节点，只提供 API 服务（默认）
         *        可以启动多个查询节点
         */
        private boolean enabled = false;
        
        /**
         * 安静时间开始（HH:mm 格式）
         */
        private String quietTimeStart = "02:00";
        
        /**
         * 安静时间结束（HH:mm 格式）
         */
        private String quietTimeEnd = "06:00";
        
        /**
         * 批次大小
         */
        private int batchSize = 500;
        
        /**
         * 批次间隔（毫秒）
         */
        private int batchIntervalMs = 1000;
        
        /**
         * 每轮处理的批次数
         */
        private int batchesPerCycle = 10;
        
        /**
         * 优雅停机超时时间（秒）
         */
        private int shutdownTimeoutSec = 30;
        
        /**
         * 同步历史消息的天数
         * 只同步 N 天**之前**的消息（旧消息），默认 365 天（1年）
         * 例如：sync-history-days=365 表示只同步 1 年前的消息
         * 
         * 设置为 0 表示只同步 5 分钟之前的消息（避免批量写入时的数据不一致问题）
         * 原因：数据源的消息是批量写入的，可能先写入 user_message 后写入 message，
         *      为避免同步时找不到对应的消息内容，需要延迟 5 分钟同步
         */
        private int syncHistoryDays = 365;
    }
}
