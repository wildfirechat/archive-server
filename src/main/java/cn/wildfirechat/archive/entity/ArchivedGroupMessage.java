package cn.wildfirechat.archive.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 群组归档消息实体 - 对应 ClickHouse 的 group_message_archive 表
 * 
 * 此表是宽表，包含：
 * - t_group_messages_x 表的字段（群组消息元数据）
 * - t_messages_x 表的字段（消息内容）
 */
@Data
public class ArchivedGroupMessage {
    
    /** 消息ID */
    private Long mid;
    
    /** 发送者用户ID */
    private String senderId;
    
    /** 群组ID */
    private String gid;
    
    /** 会话线路 */
    private Integer convLine;
    
    /** 消息内容类型 */
    private Integer contentType;
    
    /** 消息内容数据（Base64编码，来自 t_messages_x._data） */
    private String contentData;
    
    /** 可搜索关键字（来自 t_messages_x._searchable_key） */
    private String searchableKey;
    
    /** 消息发送时间 */
    private LocalDateTime messageDt;
    
    /** 群组哈希值（用于分区）：gid.hashCode() % 128 */
    private Integer groupHash;
}
