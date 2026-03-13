package cn.wildfirechat.archive.repository.source;

import cn.wildfirechat.archive.entity.GroupMessage;
import cn.wildfirechat.archive.entity.Message;
import cn.wildfirechat.archive.entity.UserMessage;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息数据源接口
 */
public interface MessageSource {

    /**
     * 根据mid列表获取消息
     */
    Message fetchMessagesByMids(int tableIndex, long mid);
    
    /**
     * 根据mid获取用户消息关联
     */
    List<UserMessage> fetchUserMessagesByMids(int tableIndex, List<Long> mids);
    
    /**
     * 获取指定表的最大ID
     */
    Long getMaxId(int tableIndex);
    
    /**
     * 根据mid确定表索引
     */
    int getTableIndexByMid(Long mid);
    
    /**
     * 获取所有表索引列表
     */
    List<Integer> getAllTableIndexes();
    
    /**
     * 获取表名
     */
    String getMessageTableName(int tableIndex);
    
    /**
     * 获取用户消息表名
     */
    String getUserMessageTableName(String userId);

    /**
     * 根据mid范围和时间范围获取用户消息列表
     * @param tableIndex 用户消息表索引 (0-127)
     * @param startMid 起始mid
     * @param limit 限制条数
     * @param startTime 开始时间（包含），可为null表示不限制
     * @param endTime 结束时间（包含），可为null表示不限制
     * @return 用户消息列表
     */
    List<UserMessage> fetchUserMessagesByMidRange(int tableIndex, Long startMid, int limit, LocalDateTime startTime, LocalDateTime endTime);
    
    /**
     * 获取指定用户消息表的最大mid
     * @param tableIndex 用户消息表索引 (0-127)
     * @return 最大mid
     */
    Long getUserTableMaxMid(int tableIndex);
    
    // ==================== 群组消息相关方法 ====================
    
    /**
     * 获取群组消息表数量
     * @return 群组消息表数量（128）
     */
    int getGroupMessageTableCount();
    
    /**
     * 获取群组消息表名
     * @param tableIndex 表索引 (0-127)
     * @return 表名，如 t_group_messages_0
     */
    String getGroupMessageTableName(int tableIndex);
    
    /**
     * 根据群组ID获取表索引
     * @param gid 群组ID
     * @return 表索引 (0-127)
     */
    int getGroupMessageTableIndex(String gid);

    /**
     * 根据mid范围和时间范围获取群组消息列表
     * @param tableIndex 群组消息表索引 (0-127)
     * @param startMid 起始mid
     * @param limit 限制条数
     * @param startTime 开始时间（包含），可为null表示不限制
     * @param endTime 结束时间（包含），可为null表示不限制
     * @return 群组消息列表
     */
    List<GroupMessage> fetchGroupMessagesByMidRange(int tableIndex, Long startMid, int limit, long startTime, long endTime);
    
    /**
     * 获取指定群组消息表的最大mid
     * @param tableIndex 群组消息表索引 (0-127)
     * @return 最大mid
     */
    Long getGroupTableMaxMid(int tableIndex);
}
