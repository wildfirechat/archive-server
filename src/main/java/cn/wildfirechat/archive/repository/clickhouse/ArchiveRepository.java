package cn.wildfirechat.archive.repository.clickhouse;

import cn.wildfirechat.archive.config.ArchiveProperties;
import cn.wildfirechat.archive.entity.ArchivedMessage;
import cn.wildfirechat.archive.dto.FetchRequest;
import cn.wildfirechat.proto.ProtoConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Repository
public class ArchiveRepository {
    
    private static final DateTimeFormatter DATE_TIME_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    private final JdbcTemplate clickhouseJdbcTemplate;
    private final ArchiveProperties archiveProperties;
    
    public ArchiveRepository(@Qualifier("clickhouseJdbcTemplate") JdbcTemplate clickhouseJdbcTemplate, 
                            ArchiveProperties archiveProperties) {
        this.clickhouseJdbcTemplate = clickhouseJdbcTemplate;
        this.archiveProperties = archiveProperties;
    }
    
    private String tableName;
    
    @PostConstruct
    public void init() {
        tableName = archiveProperties.getClickhouse().getTable();
        createTableIfNotExists();
        createProgressTableIfNotExists();
    }
    
    private void createTableIfNotExists() {
        // 使用 ReplacingMergeTree 实现幂等写入
        // ORDER BY 字段根据查询条件优化：userId, convType, convTarget, convLine, mid
        // 这样可以高效支持用户的查询条件
        // 
        // 索引说明：
        // - tokenbf_v1: n-gram 布隆过滤器索引，支持模糊搜索
        //   参数：32768(布隆过滤器大小), 3(n-gram长度), 0(哈希函数数，0表示自动)
        //   注意：有假阳性，需要在应用层二次过滤
        String sql = String.format(
            "CREATE TABLE IF NOT EXISTS %s (" +
            "    mid UInt64," +
            "    sender_id String," +
            "    conv_type UInt8," +
            "    conv_target String," +
            "    conv_line UInt32," +
            "    content_type UInt32," +
            "    content_data String," +
            "    searchable_key Nullable(String)," +
            "    user_id String," +
            "    message_dt DateTime64(3)," +
            "    user_hash UInt8," +
            "    _version UInt64 DEFAULT toUnixTimestamp64Milli(now64())," +
            "    INDEX idx_searchable_key searchable_key TYPE tokenbf_v1(32768, 3, 0) GRANULARITY 4" +
            ") ENGINE = ReplacingMergeTree(_version) " +
            "PARTITION BY user_hash " +
            "ORDER BY (user_id, conv_type, conv_target, conv_line, mid)",
            tableName
        );
        try {
            clickhouseJdbcTemplate.execute(sql);
            log.info("ClickHouse table {} created or already exists", tableName);
        } catch (Exception e) {
            log.error("Failed to create ClickHouse table: {}", e.getMessage());
        }
    }
    
    private void createProgressTableIfNotExists() {
        // 创建同步进度表，用于记录每个用户消息表的同步进度
        String sql = 
            "CREATE TABLE IF NOT EXISTS archive_progress (" +
            "    table_name String," +
            "    last_mid UInt64," +
            "    updated_at DateTime64(3)" +
            ") ENGINE = ReplacingMergeTree(updated_at) " +
            "ORDER BY table_name";
        try {
            clickhouseJdbcTemplate.execute(sql);
            log.info("ClickHouse table archive_progress created or already exists");
        } catch (Exception e) {
            log.error("Failed to create ClickHouse progress table: {}", e.getMessage());
        }
    }
    
    /**
     * 批量插入消息 - 带内存保护
     * 如果消息数量超过最大批处理大小，会分块写入
     */
    public void batchInsert(List<ArchivedMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
        int maxBatchSize = archiveProperties.getClickhouse().getMaxBatchSize();
        
        // 如果数量超过最大批处理大小，分块写入
        if (messages.size() > maxBatchSize) {
            log.debug("Batch size {} exceeds max {}, splitting", messages.size(), maxBatchSize);
            for (int i = 0; i < messages.size(); i += maxBatchSize) {
                List<ArchivedMessage> chunk = messages.subList(i, Math.min(i + maxBatchSize, messages.size()));
                doBatchInsert(chunk);
            }
        } else {
            doBatchInsert(messages);
        }
    }
    
    private void doBatchInsert(List<ArchivedMessage> messages) {
        String sql = String.format(
            "INSERT INTO %s (mid, sender_id, conv_type, conv_target, " +
            "conv_line, content_type, content_data, searchable_key, user_id, " +
            "message_dt, user_hash) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            tableName
        );
        
        try {
            clickhouseJdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ArchivedMessage msg = messages.get(i);
                    ps.setLong(1, msg.getMid());
                    ps.setString(2, msg.getSenderId());
                    ps.setInt(3, msg.getConvType());
                    ps.setString(4, msg.getConvTarget());
                    ps.setInt(5, msg.getConvLine());
                    ps.setInt(6, msg.getContentType());
                    ps.setString(7, msg.getContentData());
                    ps.setString(8, msg.getSearchableKey());
                    ps.setString(9, msg.getUserId());
                    ps.setString(10, msg.getMessageDt().format(DATE_TIME_FORMATTER));
                    ps.setInt(11, msg.getUserHash());
                }
                
                @Override
                public int getBatchSize() {
                    return messages.size();
                }
            });
        } catch (Exception e) {
            log.error("Failed to batch insert {} messages: {}", messages.size(), e.getMessage());
            throw e;
        }
    }
    
    public List<ArchivedMessage> fetchMessages(String userId, FetchRequest request) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(tableName).append(" WHERE user_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(userId);
        
        // 会话类型筛选
        if (request.getConvType() != null) {
            sql.append(" AND conv_type = ?");
            params.add(request.getConvType());
        }
        
        // 会话目标筛选
        if (request.getConvTarget() != null && !request.getConvTarget().isEmpty()) {
            sql.append(" AND conv_target = ?");
            params.add(getPrivateChatUserMessageTarget(request.getConvType(), request.getConvTarget(), userId));
        }
        
        // 会话线路筛选
        if (request.getConvLine() != null) {
            sql.append(" AND conv_line = ?");
            params.add(request.getConvLine());
        }
        
        // 消息内容类型筛选
        if (request.getContentType() != null) {
            sql.append(" AND content_type = ?");
            params.add(request.getContentType());
        }
        
        // 起始消息ID
        if (request.getStartMid() != null && request.getStartMid() > 0) {
            if (Boolean.TRUE.equals(request.getBefore())) {
                sql.append(" AND mid < ?");
            } else {
                sql.append(" AND mid > ?");
            }
            params.add(request.getStartMid());
        }
        
        // 排序
        if (Boolean.TRUE.equals(request.getBefore())) {
            sql.append(" ORDER BY mid DESC");
        } else {
            sql.append(" ORDER BY mid ASC");
        }
        
        // 限制条数
        int limit = request.getLimit() != null ? request.getLimit() : 20;
        sql.append(" LIMIT ?");
        params.add(limit);

        // 修正单聊的会话target
        List<ArchivedMessage> archivedMessages = clickhouseJdbcTemplate.query(sql.toString(), archivedMessageRowMapper, params.toArray());
        if(request.getConvType() == 0 && !archivedMessages.isEmpty()) {
            archivedMessages.forEach(archivedMessage -> archivedMessage.setConvTarget(request.getConvTarget()));
        }
        return archivedMessages;
    }
    
    public List<ArchivedMessage> searchMessages(String userId, String keyword, FetchRequest request) {
        // 使用布隆过滤器索引进行预过滤，然后在 Java 层进行二次过滤排除假阳性
        // 由于 tokenbf_v1 索引有假阳性，需要查询更多数据然后在应用层精确过滤
        int queryLimit = calculateQueryLimit(request.getLimit());
        
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(tableName).append(" WHERE user_id = ?");
        sql.append(" AND searchable_key LIKE ?");
        
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.add("%" + keyword + "%");
        
        // 会话类型筛选
        if (request.getConvType() != null) {
            sql.append(" AND conv_type = ?");
            params.add(request.getConvType());
        }
        
        // 会话目标筛选
        if (request.getConvTarget() != null && !request.getConvTarget().isEmpty()) {
            sql.append(" AND conv_target = ?");
            params.add(getPrivateChatUserMessageTarget(request.getConvType(), request.getConvTarget(), userId));
        }
        
        // 会话线路筛选
        if (request.getConvLine() != null) {
            sql.append(" AND conv_line = ?");
            params.add(request.getConvLine());
        }
        
        // 消息内容类型筛选
        if (request.getContentType() != null) {
            sql.append(" AND content_type = ?");
            params.add(request.getContentType());
        }
        
        // 起始消息ID
        if (request.getStartMid() != null) {
            if (Boolean.TRUE.equals(request.getBefore())) {
                sql.append(" AND mid < ?");
            } else {
                sql.append(" AND mid > ?");
            }
            params.add(request.getStartMid());
        }
        
        // 排序
        if (Boolean.TRUE.equals(request.getBefore())) {
            sql.append(" ORDER BY mid DESC");
        } else {
            sql.append(" ORDER BY mid ASC");
        }
        
        // 限制条数（多查一些以补偿假阳性的过滤）
        sql.append(" LIMIT ?");
        params.add(queryLimit);
        
        List<ArchivedMessage> candidates = clickhouseJdbcTemplate.query(
            sql.toString(), archivedMessageRowMapper, params.toArray());
        
        // 二次过滤：排除假阳性，精确匹配关键词
        List<ArchivedMessage> archivedMessages =  filterByKeyword(candidates, keyword, request.getLimit());

        // 修正单聊的会话target
        if(request.getConvType() == 0 && !archivedMessages.isEmpty()) {
            archivedMessages.forEach(archivedMessage -> archivedMessage.setConvTarget(request.getConvTarget()));
        }
        return archivedMessages;
    }
    
    /**
     * 计算查询限制数量，预留空间用于过滤假阳性
     * 假阳性率约 1-10%，预留 30% 的缓冲
     */
    private int calculateQueryLimit(Integer requestedLimit) {
        int baseLimit = requestedLimit != null ? requestedLimit : 20;
        // 预留 50% 的缓冲，最小查询 50 条
        int queryLimit = (int) (baseLimit * 1.5);
        return Math.max(queryLimit, 50);
    }
    
    /**
     * 在 Java 层精确过滤搜索结果，排除布隆过滤器的假阳性
     * 
     * @param candidates 候选结果（可能包含假阳性）
     * @param keyword 搜索关键词
     * @param limit 返回数量限制
     * @return 精确匹配的结果
     */
    private List<ArchivedMessage> filterByKeyword(List<ArchivedMessage> candidates, 
                                                   String keyword, 
                                                   Integer limit) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 关键词小写化用于不区分大小写匹配
        String lowerKeyword = keyword.toLowerCase();
        
        List<ArchivedMessage> filtered = new ArrayList<>();
        int targetLimit = limit != null ? limit : 20;
        
        for (ArchivedMessage msg : candidates) {
            // 精确匹配：检查 searchable_key 是否真正包含关键词
            if (msg.getSearchableKey() != null && 
                msg.getSearchableKey().toLowerCase().contains(lowerKeyword)) {
                filtered.add(msg);
                if (filtered.size() >= targetLimit) {
                    break;
                }
            }
        }
        
        // 记录过滤情况（用于监控假阳性率）
        if (candidates.size() > filtered.size()) {
            log.debug("Search filtered: {} candidates -> {} results (filtered {} false positives)", 
                candidates.size(), filtered.size(), candidates.size() - filtered.size());
        }
        
        return filtered;
    }
    
    public Long getMaxMid() {
        String sql = String.format("SELECT max(mid) FROM %s", tableName);
        try {
            Long maxMid = clickhouseJdbcTemplate.queryForObject(sql, Long.class);
            return maxMid != null ? maxMid : 0L;
        } catch (Exception e) {
            log.error("Failed to get max mid: {}", e.getMessage());
            return 0L;
        }
    }
    
    /**
     * 通用查询单值方法
     */
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        return clickhouseJdbcTemplate.queryForObject(sql, requiredType, args);
    }
    
    /**
     * 通用更新方法
     */
    public int update(String sql, Object... args) {
        return clickhouseJdbcTemplate.update(sql, args);
    }
    
    private final RowMapper<ArchivedMessage> archivedMessageRowMapper = (rs, rowNum) -> {
        ArchivedMessage msg = new ArchivedMessage();
        msg.setMid(rs.getLong("mid"));
        msg.setSenderId(rs.getString("sender_id"));
        msg.setConvType(rs.getInt("conv_type"));
        msg.setConvTarget(rs.getString("conv_target"));
        msg.setConvLine(rs.getInt("conv_line"));
        msg.setContentType(rs.getInt("content_type"));
        msg.setContentData(rs.getString("content_data"));
        msg.setSearchableKey(rs.getString("searchable_key"));
        msg.setUserId(rs.getString("user_id"));
        msg.setMessageDt(rs.getTimestamp("message_dt").toLocalDateTime());
        msg.setUserHash(rs.getInt("user_hash"));
        return msg;
    };

    public static String getPrivateChatUserMessageTarget(int conversationType, String target, String userId) {
        if(conversationType == ProtoConstants.ConversationType.ConversationType_Private && !target.contains("|")) {
            return target.compareTo(userId) > 0 ? (userId + "|" + target) : (target + "|" + userId);
        } else {
            return target;
        }
    }
}
