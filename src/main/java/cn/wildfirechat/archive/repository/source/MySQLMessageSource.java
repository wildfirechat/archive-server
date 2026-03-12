package cn.wildfirechat.archive.repository.source;

import cn.wildfirechat.archive.entity.Message;
import cn.wildfirechat.archive.entity.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "archive.source-type", havingValue = "mysql", matchIfMissing = true)
public class MySQLMessageSource implements MessageSource {
    
    private static final int MESSAGE_TABLE_COUNT = 36;
    private static final int USER_MESSAGE_TABLE_COUNT = 128;
    
    private final JdbcTemplate jdbcTemplate;
    
    // 参考 MessageShardingUtil 的常量
    private static final int NODE_ID_WIDTH = 6;
    private static final int ROTATE_ID_WIDTH = 15;
    private static final long T201801010000 = 1514736000000L;
    
    private final RowMapper<Message> messageRowMapper = (rs, rowNum) -> {
        Message msg = new Message();
        msg.setFrom(rs.getString("_from"));
        msg.setData(rs.getBytes("_data"));
        msg.setSearchableKey(rs.getString("_searchable_key"));
        Timestamp dt = rs.getTimestamp("_dt");
        msg.setDt(dt != null ? dt.toLocalDateTime() : null);
        return msg;
    };
    
    private final RowMapper<UserMessage> userMessageRowMapper = (rs, rowNum) -> {
        UserMessage um = new UserMessage();
        um.setMid(rs.getLong("_mid"));
        um.setUid(rs.getString("_uid"));
        Timestamp dt = rs.getTimestamp("_dt");
        um.setDt(dt != null ? dt.toLocalDateTime() : null);
        um.setLine(rs.getInt("_line"));
        um.setType(rs.getInt("_type"));
        um.setTarget(rs.getString("_target"));
        um.setContType(rs.getInt("_cont_type"));
        return um;
    };
    
    @Override
    public Message fetchMessagesByMids(int tableIndex, long mid) {
        String tableName = getMessageTableName(tableIndex);
        // mid 是 long 基本类型，不存在 SQL 注入风险
        String sql = String.format("SELECT _from, _data, _searchable_key, _dt FROM %s WHERE _mid = %d ORDER BY _dt DESC, id DESC LIMIT 1", tableName, mid);
        try {
            List<Message> messages = jdbcTemplate.query(sql, messageRowMapper);
            if(messages.isEmpty()) {
                return null;
            } else {
                return messages.get(0);
            }
        } catch (Exception e) {
            log.error("Failed to fetch messages by mids from {}: {}", tableName, e.getMessage());
            return null;
        }
    }
    
    @Override
    public List<UserMessage> fetchUserMessagesByMids(int tableIndex, List<Long> mids) {
        if (mids == null || mids.isEmpty()) {
            return new ArrayList<>();
        }
        String tableName = getUserMessageTableName(tableIndex);
        String inClause = String.join(",", java.util.Collections.nCopies(mids.size(), "?"));
        String sql = String.format("SELECT * FROM %s WHERE _mid IN (%s)", tableName, inClause);
        try {
            return jdbcTemplate.query(sql, userMessageRowMapper, mids.toArray());
        } catch (Exception e) {
            log.error("Failed to fetch user messages from {}: {}", tableName, e.getMessage());
            return new ArrayList<>();
        }
    }
    
    @Override
    public Long getMaxId(int tableIndex) {
        String tableName = getMessageTableName(tableIndex);
        String sql = String.format("SELECT MAX(id) FROM %s", tableName);
        try {
            Long maxId = jdbcTemplate.queryForObject(sql, Long.class);
            return maxId != null ? maxId : 0L;
        } catch (Exception e) {
            log.error("Failed to get max id from {}: {}", tableName, e.getMessage());
            return 0L;
        }
    }
    
    @Override
    public int getTableIndexByMid(Long mid) {
        // 参考 MessageShardingUtil.getMessageTable
        Calendar calendar = Calendar.getInstance();
        if (mid != null && mid != Long.MAX_VALUE && mid != 0) {
            mid >>= (NODE_ID_WIDTH + ROTATE_ID_WIDTH);
            java.util.Date date = new java.util.Date(mid + T201801010000);
            calendar.setTime(date);
        } else {
            calendar.setTime(new java.util.Date());
        }
        int month = calendar.get(Calendar.MONTH);
        int year = calendar.get(Calendar.YEAR);
        year %= 3;
        return year * 12 + month;
    }
    
    @Override
    public List<Integer> getAllTableIndexes() {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < MESSAGE_TABLE_COUNT; i++) {
            indexes.add(i);
        }
        return indexes;
    }
    
    @Override
    public String getMessageTableName(int tableIndex) {
        return String.format("t_messages_%d", tableIndex);
    }
    
    @Override
    public String getUserMessageTableName(String userId) {
        int index = Math.abs(userId.hashCode()) % USER_MESSAGE_TABLE_COUNT;
        return getUserMessageTableName(index);
    }
    
    private String getUserMessageTableName(int tableIndex) {
        return String.format("t_user_messages_%d", tableIndex);
    }
    
    @Override
    public List<UserMessage> fetchUserMessagesByMidRange(int tableIndex, Long startMid, int limit) {
        return fetchUserMessagesByMidRange(tableIndex, startMid, limit, null, null);
    }
    
    @Override
    public List<UserMessage> fetchUserMessagesByMidRange(int tableIndex, Long startMid, int limit, LocalDateTime startTime, LocalDateTime endTime) {
        String tableName = getUserMessageTableName(tableIndex);
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(String.format("SELECT * FROM %s WHERE _mid > ?", tableName));
        
        List<Object> params = new ArrayList<>();
        params.add(startMid);
        
        // 添加时间筛选条件
        if (startTime != null) {
            sqlBuilder.append(" AND _dt >= ?");
            params.add(startTime);
        }
        if (endTime != null) {
            sqlBuilder.append(" AND _dt <= ?");
            params.add(endTime);
        }
        
        sqlBuilder.append(" ORDER BY _mid ASC LIMIT ?");
        params.add(limit);
        
        try {
            return jdbcTemplate.query(sqlBuilder.toString(), userMessageRowMapper, params.toArray());
        } catch (Exception e) {
            log.error("Failed to fetch user messages from {}: {}", tableName, e.getMessage());
            return new ArrayList<>();
        }
    }
    
    @Override
    public Long getUserTableMaxMid(int tableIndex) {
        String tableName = getUserMessageTableName(tableIndex);
        String sql = String.format("SELECT MAX(_mid) FROM %s", tableName);
        try {
            Long maxMid = jdbcTemplate.queryForObject(sql, Long.class);
            return maxMid != null ? maxMid : 0L;
        } catch (Exception e) {
            log.error("Failed to get max mid from {}: {}", tableName, e.getMessage());
            return 0L;
        }
    }
}
