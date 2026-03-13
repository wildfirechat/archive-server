package cn.wildfirechat.archive.repository.clickhouse;

import cn.wildfirechat.archive.config.ArchiveProperties;
import cn.wildfirechat.archive.dto.FetchRequest;
import cn.wildfirechat.archive.dto.SearchRequest;
import cn.wildfirechat.archive.entity.ArchivedGroupMessage;
import cn.wildfirechat.archive.entity.ArchivedMessage;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ClickHouse 归档数据访问层
 * 
 * 管理两个表：
 * - message_archive: 普通消息归档表
 * - group_message_archive: 群组消息归档表（超级群组）
 */
@Slf4j
@Repository
public class ArchiveRepository {
    
    // 表名常量
    public static final String TABLE_MESSAGE_ARCHIVE = "message_archive";
    public static final String TABLE_GROUP_MESSAGE_ARCHIVE = "group_message_archive";
    public static final String TABLE_ARCHIVE_PROGRESS = "archive_progress";
    
    private static final DateTimeFormatter DATE_TIME_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    private final JdbcTemplate clickhouseJdbcTemplate;
    private final ArchiveProperties archiveProperties;
    
    public ArchiveRepository(@Qualifier("clickhouseJdbcTemplate") JdbcTemplate clickhouseJdbcTemplate, 
                            ArchiveProperties archiveProperties) {
        this.clickhouseJdbcTemplate = clickhouseJdbcTemplate;
        this.archiveProperties = archiveProperties;
    }

    // ==================== 普通消息表操作 ====================
    
    /**
     * 批量插入普通消息
     */
    public void batchInsertMessages(List<ArchivedMessage> messages) {
        if (isEmpty(messages)) return;
        
        batchInsertWithChunk(messages, TABLE_MESSAGE_ARCHIVE, this::setMessageStatementValues);
    }
    
    private void setMessageStatementValues(PreparedStatement ps, ArchivedMessage msg) throws SQLException {
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
    
    /**
     * 查询普通消息列表
     */
    public List<ArchivedMessage> fetchMessages(String userId, FetchRequest request) {
        SqlBuilder sql = new SqlBuilder("SELECT * FROM " + TABLE_MESSAGE_ARCHIVE);
        sql.where("user_id = ?", userId);
        
        // 添加筛选条件
        addConvTypeFilter(sql, request.getConvType());
        addConvTargetFilter(sql, getPrivateChatUserMessageTarget(request.getConvType(), request.getConvTarget(), userId), userId);
        addConvLineFilter(sql, request.getConvLine());
        addContentTypeFilter(sql, request.getContentType());
        addStartMidFilter(sql, request.getStartMid(), request.getBefore());
        
        // 排序和分页
        addOrderByMid(sql, request.getBefore());
        addLimit(sql, request.getLimit());
        
        List<ArchivedMessage> messages = clickhouseJdbcTemplate.query(
            sql.build(), messageRowMapper, sql.getParams());
        
        // 修正单聊的会话target
        fixPrivateChatTarget(messages, request);
        return messages;
    }
    
    /**
     * 搜索普通消息
     */
    public List<ArchivedMessage> searchMessages(String userId, String keyword, SearchRequest request) {
        SqlBuilder sql = new SqlBuilder("SELECT * FROM " + TABLE_MESSAGE_ARCHIVE);
        sql.where("user_id = ?", userId);
        sql.where("searchable_key LIKE ?", "%" + keyword + "%");
        
        // 添加筛选条件
        addConvTypeFilter(sql, request.getConvType());
        addConvTargetFilter(sql, getPrivateChatUserMessageTarget(request.getConvType(), request.getConvTarget(), userId), userId);
        addConvLineFilter(sql, request.getConvLine());
        addContentTypeFilter(sql, request.getContentType());
        addStartMidFilter(sql, request.getStartMid(), request.getBefore());
        
        // 排序和分页（搜索时多查一些以补偿假阳性）
        addOrderByMid(sql, request.getBefore());
        int queryLimit = calculateQueryLimit(request.getLimit());
        addLimit(sql, queryLimit);
        
        List<ArchivedMessage> candidates = clickhouseJdbcTemplate.query(
            sql.build(), messageRowMapper, sql.getParams());
        
        // 二次过滤假阳性
        List<ArchivedMessage> filtered = filterByKeyword(candidates, keyword, request.getLimit());
        fixPrivateChatTarget(filtered, request);
        return filtered;
    }
    
    // ==================== 群组消息表操作 ====================
    
    /**
     * 批量插入群组消息
     */
    public void batchInsertGroupMessages(List<ArchivedGroupMessage> messages) {
        if (isEmpty(messages)) return;
        
        batchInsertWithChunk(messages, TABLE_GROUP_MESSAGE_ARCHIVE, this::setGroupMessageStatementValues);
    }
    
    private void setGroupMessageStatementValues(PreparedStatement ps, ArchivedGroupMessage msg) throws SQLException {
        ps.setLong(1, msg.getMid());
        ps.setString(2, msg.getSenderId());
        ps.setString(3, msg.getGid());
        ps.setInt(4, msg.getConvLine());
        ps.setInt(5, msg.getContentType());
        ps.setString(6, msg.getContentData());
        ps.setString(7, msg.getSearchableKey());
        ps.setString(8, msg.getMessageDt().format(DATE_TIME_FORMATTER));
        ps.setInt(9, msg.getGroupHash());
    }
    
    /**
     * 查询群组消息列表
     */
    public List<ArchivedGroupMessage> fetchGroupMessages(String gid, FetchRequest request) {
        SqlBuilder sql = new SqlBuilder("SELECT * FROM " + TABLE_GROUP_MESSAGE_ARCHIVE);
        sql.where("gid = ?", gid);
        
        // 添加筛选条件
        addConvLineFilter(sql, request.getConvLine());
        addContentTypeFilter(sql, request.getContentType());
        addStartMidFilter(sql, request.getStartMid(), request.getBefore());
        
        // 排序和分页
        addOrderByMid(sql, request.getBefore());
        addLimit(sql, request.getLimit());
        
        return clickhouseJdbcTemplate.query(sql.build(), groupMessageRowMapper, sql.getParams());
    }
    
    /**
     * 在指定群组中搜索消息
     */
    public List<ArchivedGroupMessage> searchGroupMessages(String gid, String keyword, SearchRequest request) {
        SqlBuilder sql = new SqlBuilder("SELECT * FROM " + TABLE_GROUP_MESSAGE_ARCHIVE);
        sql.where("gid = ?", gid);
        sql.where("searchable_key LIKE ?", "%" + keyword + "%");
        
        // 添加筛选条件
        addConvLineFilter(sql, request.getConvLine());
        addContentTypeFilter(sql, request.getContentType());
        addStartMidFilter(sql, request.getStartMid(), request.getBefore());
        
        // 排序和分页
        addOrderByMid(sql, request.getBefore());
        int queryLimit = calculateQueryLimit(request.getLimit());
        addLimit(sql, queryLimit);
        
        List<ArchivedGroupMessage> candidates = clickhouseJdbcTemplate.query(
            sql.build(), groupMessageRowMapper, sql.getParams());
        
        return filterGroupMessagesByKeyword(candidates, keyword, request.getLimit());
    }
    
    /**
     * 搜索所有群组消息
     */
    public List<ArchivedGroupMessage> searchAllGroupMessages(String keyword, SearchRequest request) {
        SqlBuilder sql = new SqlBuilder("SELECT * FROM " + TABLE_GROUP_MESSAGE_ARCHIVE);
        sql.where("searchable_key LIKE ?", "%" + keyword + "%");
        
        // 可选：按群组ID筛选
        if (hasText(request.getConvTarget())) {
            sql.where("gid = ?", request.getConvTarget());
        }
        
        // 添加筛选条件
        addConvLineFilter(sql, request.getConvLine());
        addContentTypeFilter(sql, request.getContentType());
        addStartMidFilter(sql, request.getStartMid(), request.getBefore());
        
        // 排序和分页
        addOrderByMid(sql, request.getBefore());
        int queryLimit = calculateQueryLimit(request.getLimit());
        addLimit(sql, queryLimit);
        
        List<ArchivedGroupMessage> candidates = clickhouseJdbcTemplate.query(
            sql.build(), groupMessageRowMapper, sql.getParams());
        
        return filterGroupMessagesByKeyword(candidates, keyword, request.getLimit());
    }
    
    // ==================== 进度表操作 ====================
    
    public Long getLastMid(String tableName) {
        String sql = String.format("SELECT last_mid FROM %s FINAL WHERE table_name = ?", TABLE_ARCHIVE_PROGRESS);
        try {
            return queryForObject(sql, Long.class, tableName);
        } catch (Exception e) {
            return 0L;
        }
    }
    
    public void updateProgress(String tableName, long lastMid) {
        String sql = String.format(
            "INSERT INTO %s (table_name, last_mid, updated_at) VALUES (?, ?, ?)",
            TABLE_ARCHIVE_PROGRESS
        );
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneId.of("UTC")));
        update(sql, tableName, lastMid, now);
    }
    
    // ==================== 通用方法 ====================
    
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        return clickhouseJdbcTemplate.queryForObject(sql, requiredType, args);
    }
    
    public int update(String sql, Object... args) {
        return clickhouseJdbcTemplate.update(sql, args);
    }
    
    // ==================== 私有工具方法 ====================
    
    private <T> void batchInsertWithChunk(List<T> messages, String tableName, 
                                          StatementValueSetter<T> valueSetter) {
        int maxBatchSize = archiveProperties.getClickhouse().getMaxBatchSize();
        
        if (messages.size() > maxBatchSize) {
            log.debug("Batch size {} exceeds max {}, splitting", messages.size(), maxBatchSize);
            for (int i = 0; i < messages.size(); i += maxBatchSize) {
                List<T> chunk = messages.subList(i, Math.min(i + maxBatchSize, messages.size()));
                doBatchInsert(chunk, tableName, valueSetter);
            }
        } else {
            doBatchInsert(messages, tableName, valueSetter);
        }
    }
    
    private <T> void doBatchInsert(List<T> messages, String tableName, 
                                   StatementValueSetter<T> valueSetter) {
        String sql = buildInsertSql(tableName, messages.get(0));
        
        try {
            clickhouseJdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    valueSetter.setValues(ps, messages.get(i));
                }
                
                @Override
                public int getBatchSize() {
                    return messages.size();
                }
            });
        } catch (Exception e) {
            log.error("Failed to batch insert {} messages into {}: {}", 
                messages.size(), tableName, e.getMessage());
            throw new RuntimeException("Batch insert failed", e);
        }
    }
    
    private String buildInsertSql(String tableName, Object sample) {
        if (sample instanceof ArchivedMessage) {
            return String.format(
                "INSERT INTO %s (mid, sender_id, conv_type, conv_target, " +
                "conv_line, content_type, content_data, searchable_key, user_id, " +
                "message_dt, user_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tableName
            );
        } else {
            return String.format(
                "INSERT INTO %s (mid, sender_id, gid, conv_line, " +
                "content_type, content_data, searchable_key, message_dt, group_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tableName
            );
        }
    }
    
    // SQL 条件构建辅助方法
    private void addConvTypeFilter(SqlBuilder sql, Integer convType) {
        if (convType != null) {
            sql.where("conv_type = ?", convType);
        }
    }
    
    private void addConvTargetFilter(SqlBuilder sql, String convTarget, String userId) {
        if (hasText(convTarget)) {
            sql.where("conv_target = ?", getPrivateChatUserMessageTarget(null, convTarget, userId));
        }
    }
    
    private void addConvLineFilter(SqlBuilder sql, Integer convLine) {
        if (convLine != null) {
            sql.where("conv_line = ?", convLine);
        }
    }
    
    private void addContentTypeFilter(SqlBuilder sql, Integer contentType) {
        if (contentType != null) {
            sql.where("content_type = ?", contentType);
        }
    }
    
    private void addStartMidFilter(SqlBuilder sql, Long startMid, Boolean before) {
        if (startMid != null && startMid > 0) {
            String operator = Boolean.TRUE.equals(before) ? "<" : ">";
            sql.where("mid " + operator + " ?", startMid);
        }
    }
    
    private void addOrderByMid(SqlBuilder sql, Boolean before) {
        String direction = Boolean.TRUE.equals(before) ? "DESC" : "ASC";
        sql.orderBy("mid " + direction);
    }
    
    private void addLimit(SqlBuilder sql, Integer limit) {
        sql.limit(limit != null ? limit : 20);
    }
    
    private void fixPrivateChatTarget(List<ArchivedMessage> messages, FetchRequest request) {
        if (request.getConvType() != null && request.getConvType() == 0 && !messages.isEmpty()) {
            messages.forEach(m -> m.setConvTarget(request.getConvTarget()));
        }
    }
    
    private int calculateQueryLimit(Integer requestedLimit) {
        int baseLimit = requestedLimit != null ? requestedLimit : 20;
        return Math.max((int) (baseLimit * 1.5), 50);
    }
    
    private <T> List<T> filterByKeyword(List<T> candidates, String keyword, Integer limit) {
        if (isEmpty(candidates)) return new ArrayList<>();
        
        String lowerKeyword = keyword.toLowerCase();
        List<T> filtered = new ArrayList<>();
        int targetLimit = limit != null ? limit : 20;
        
        for (T item : candidates) {
            String searchableKey = getSearchableKey(item);
            if (searchableKey != null && searchableKey.toLowerCase().contains(lowerKeyword)) {
                filtered.add(item);
                if (filtered.size() >= targetLimit) break;
            }
        }
        
        logFilterResult(candidates.size(), filtered.size());
        return filtered;
    }
    
    private List<ArchivedGroupMessage> filterGroupMessagesByKeyword(
            List<ArchivedGroupMessage> candidates, String keyword, Integer limit) {
        if (isEmpty(candidates)) return new ArrayList<>();
        
        String lowerKeyword = keyword.toLowerCase();
        List<ArchivedGroupMessage> filtered = new ArrayList<>();
        int targetLimit = limit != null ? limit : 20;
        
        for (ArchivedGroupMessage msg : candidates) {
            if (msg.getSearchableKey() != null && 
                msg.getSearchableKey().toLowerCase().contains(lowerKeyword)) {
                filtered.add(msg);
                if (filtered.size() >= targetLimit) break;
            }
        }
        
        logFilterResult(candidates.size(), filtered.size());
        return filtered;
    }
    
    private String getSearchableKey(Object item) {
        if (item instanceof ArchivedMessage) {
            return ((ArchivedMessage) item).getSearchableKey();
        } else if (item instanceof ArchivedGroupMessage) {
            return ((ArchivedGroupMessage) item).getSearchableKey();
        }
        return null;
    }
    
    private void logFilterResult(int candidatesSize, int filteredSize) {
        if (candidatesSize > filteredSize) {
            log.debug("Search filtered: {} candidates -> {} results (filtered {} false positives)", 
                candidatesSize, filteredSize, candidatesSize - filteredSize);
        }
    }
    
    private static String getPrivateChatUserMessageTarget(Integer conversationType, String target, String userId) {
        if (conversationType != null && conversationType == ProtoConstants.ConversationType.ConversationType_Private 
                && target != null && !target.contains("|") && userId != null) {
            return target.compareTo(userId) > 0 ? (userId + "|" + target) : (target + "|" + userId);
        }
        return target;
    }
    
    private <T> boolean isEmpty(List<T> list) {
        return list == null || list.isEmpty();
    }
    
    private boolean hasText(String str) {
        return str != null && !str.isEmpty();
    }
    
    // ==================== RowMappers ====================
    
    private final RowMapper<ArchivedMessage> messageRowMapper = (rs, rowNum) -> {
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
    
    private final RowMapper<ArchivedGroupMessage> groupMessageRowMapper = (rs, rowNum) -> {
        ArchivedGroupMessage msg = new ArchivedGroupMessage();
        msg.setMid(rs.getLong("mid"));
        msg.setSenderId(rs.getString("sender_id"));
        msg.setGid(rs.getString("gid"));
        msg.setConvLine(rs.getInt("conv_line"));
        msg.setContentType(rs.getInt("content_type"));
        msg.setContentData(rs.getString("content_data"));
        msg.setSearchableKey(rs.getString("searchable_key"));
        msg.setMessageDt(rs.getTimestamp("message_dt").toLocalDateTime());
        msg.setGroupHash(rs.getInt("group_hash"));
        return msg;
    };
    
    // ==================== 函数式接口 ====================
    
    @FunctionalInterface
    private interface StatementValueSetter<T> {
        void setValues(PreparedStatement ps, T item) throws SQLException;
    }
    
    // ==================== SQL 构建器 ====================
    
    private static class SqlBuilder {
        private final StringBuilder sql = new StringBuilder();
        private final List<Object> params = new ArrayList<>();
        private boolean hasWhere = false;
        
        SqlBuilder(String select) {
            sql.append(select);
        }
        
        void where(String condition, Object... values) {
            if (!hasWhere) {
                sql.append(" WHERE ");
                hasWhere = true;
            } else {
                sql.append(" AND ");
            }
            sql.append(condition);
            for (Object value : values) {
                params.add(value);
            }
        }
        
        void orderBy(String order) {
            sql.append(" ORDER BY ").append(order);
        }
        
        void limit(int limit) {
            sql.append(" LIMIT ").append(limit);
        }
        
        String build() {
            return sql.toString();
        }
        
        Object[] getParams() {
            return params.toArray();
        }
    }
}
