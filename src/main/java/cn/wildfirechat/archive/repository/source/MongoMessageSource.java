package cn.wildfirechat.archive.repository.source;

import cn.wildfirechat.archive.config.ArchiveProperties;
import cn.wildfirechat.archive.entity.Message;
import cn.wildfirechat.archive.entity.UserMessage;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.conversions.Bson;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Projections.*;

@Slf4j
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "archive.source-type", havingValue = "mongodb")
public class MongoMessageSource implements MessageSource {
    
    private static final int MESSAGE_TABLE_COUNT = 36;
    private static final int USER_MESSAGE_TABLE_COUNT = 128;
    
    // 参考 MessageShardingUtil 的常量
    private static final int NODE_ID_WIDTH = 6;
    private static final int ROTATE_ID_WIDTH = 15;
    private static final long T201801010000 = 1514736000000L;
    
    private final MongoClient mongoClient;
    private final ArchiveProperties archiveProperties;
    
    private MongoDatabase getDatabase() {
        return mongoClient.getDatabase(archiveProperties.getMongodb().getDatabase());
    }
    
    private MongoCollection<Document> getMessageCollection(int tableIndex) {
        return getDatabase().getCollection(getMessageTableName(tableIndex));
    }
    
    private MongoCollection<Document> getUserMessageCollection(int tableIndex) {
        return getDatabase().getCollection(getUserMessageTableName(tableIndex));
    }
    
    private Message documentToMessage(Document doc) {
        if (doc == null) return null;
        Message msg = new Message();
        msg.setFrom(doc.getString("_from"));

        Object dataObj = doc.get("_data");
        if (dataObj instanceof Binary) {
            msg.setData(((Binary) dataObj).getData());
        } else if (dataObj instanceof byte[]) {
            msg.setData((byte[]) dataObj);
        }

        msg.setSearchableKey(doc.getString("_searchable_key"));
        
        Date dt = doc.getDate("_dt");
        if (dt != null) {
            // MongoDB 存储的是 UTC 时间，保持 UTC 不变
            msg.setDt(LocalDateTime.ofInstant(dt.toInstant(), ZoneId.of("UTC")));
        }
        return msg;
    }
    
    private UserMessage documentToUserMessage(Document doc) {
        if (doc == null) return null;
        UserMessage um = new UserMessage();
        um.setMid(doc.getLong("_mid"));
        um.setUid(doc.getString("_uid"));
        
        Date dt = doc.getDate("_dt");
        if (dt != null) {
            // MongoDB 存储的是 UTC 时间，保持 UTC 不变
            um.setDt(LocalDateTime.ofInstant(dt.toInstant(), ZoneId.of("UTC")));
        }
        
        um.setLine(doc.getInteger("_line", 0));
        um.setType(doc.getInteger("_type", 0));
        um.setTarget(doc.getString("_target"));
        um.setContType(doc.getInteger("_cont_type", 0));
        return um;
    }

    @Override
    public Message fetchMessagesByMids(int tableIndex, long mid) {
        // 1. 构建投影条件：排除_id，保留指定字段（推荐用Projections工具类，更语义化）
        Bson projection = fields(
                excludeId(), // 排除_id字段（等价于append("_id", 0)）
                include("_from", "_data", "_searchable_key", "_dt") // 保留指定字段（等价于append("字段", 1)）
        );

        // 2. 查询并处理结果（limit(1) + first() 替代forEach，更高效）
        MongoCollection<Document> collection = getMessageCollection(tableIndex);
        Document doc = collection.find(eq("_id", mid)) // 根据_id查询
                .projection(projection) // 应用投影
                .first(); // 获取第一条（无结果返回null，替代limit(1)+forEach）

        // 3. 空值处理：避免空指针，明确无结果的情况
        if (doc != null) {
            return documentToMessage(doc);
        } else {
            // 可选：记录日志/抛出异常/返回默认值，根据业务需求处理
            log.info("未找到ID为 {} 的消息文档", mid);
            return null;
        }
    }
    
    @Override
    public List<UserMessage> fetchUserMessagesByMids(int tableIndex, List<Long> mids) {
        List<UserMessage> userMessages = new ArrayList<>();
        if (mids == null || mids.isEmpty()) {
            return userMessages;
        }
        try {

            getUserMessageCollection(tableIndex)
                .find(Filters.in("_mid", mids))
                .forEach(doc -> userMessages.add(documentToUserMessage(doc)));
        } catch (Exception e) {
            log.error("Failed to fetch user messages from table {}: {}", tableIndex, e.getMessage());
        }
        return userMessages;
    }
    
    @Override
    public Long getMaxId(int tableIndex) {
        try {
            Document doc = getMessageCollection(tableIndex)
                .find()
                .sort(new Document("id", -1))
                .first();
            if (doc != null && doc.getLong("id") != null) {
                return doc.getLong("id");
            }
        } catch (Exception e) {
            log.error("Failed to get max id from table {}: {}", tableIndex, e.getMessage());
        }
        return 0L;
    }
    
    @Override
    public int getTableIndexByMid(Long mid) {
        // 参考 MessageShardingUtil.getMessageTable
        Calendar calendar = Calendar.getInstance();
        if (mid != null && mid != Long.MAX_VALUE && mid != 0) {
            mid >>= (NODE_ID_WIDTH + ROTATE_ID_WIDTH);
            Date date = new Date(mid + T201801010000);
            calendar.setTime(date);
        } else {
            calendar.setTime(new Date());
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
        List<UserMessage> userMessages = new ArrayList<>();
        try {
            // 构建查询条件
            List<org.bson.conversions.Bson> filters = new ArrayList<>();
            filters.add(Filters.gt("_mid", startMid));
            
            // 添加时间筛选条件（输入的 LocalDateTime 是 UTC 时间）
            if (startTime != null) {
                filters.add(Filters.gte("_dt", Date.from(startTime.atZone(ZoneId.of("UTC")).toInstant())));
            }
            if (endTime != null) {
                filters.add(Filters.lte("_dt", Date.from(endTime.atZone(ZoneId.of("UTC")).toInstant())));
            }
            
            getUserMessageCollection(tableIndex)
                .find(Filters.and(filters))
                .sort(new Document("_mid", 1))
                .limit(limit)
                .forEach(doc -> userMessages.add(documentToUserMessage(doc)));
        } catch (Exception e) {
            log.error("Failed to fetch user messages from table {}: {}", tableIndex, e.getMessage());
        }
        return userMessages;
    }
    
    @Override
    public Long getUserTableMaxMid(int tableIndex) {
        try {
            Document doc = getUserMessageCollection(tableIndex)
                .find()
                .sort(new Document("_mid", -1))
                .first();
            if (doc != null && doc.getLong("_mid") != null) {
                return doc.getLong("_mid");
            }
        } catch (Exception e) {
            log.error("Failed to get max mid from user table {}: {}", tableIndex, e.getMessage());
        }
        return 0L;
    }
}
