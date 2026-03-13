package cn.wildfirechat.archive.entity;

import lombok.Data;

/**
 * 群组消息实体 - 对应 t_group_messages_x 表
 * 
 * 注意：此表不包含消息具体内容（content_data），
 * 需要通过 _mid 关联到 t_messages_x 表获取消息内容
 */
@Data
public class GroupMessage {
    
    /** 消息ID（关联 t_messages_x 表的 _mid） */
    private Long mid;
    
    /** 发送者用户ID */
    private String sender;
    
    /** 群组ID */
    private String gid;
    
    /** 会话线路 */
    private Integer line;
    
    /** 消息内容类型 */
    private Integer contType;
    
    /** 消息发送时间（毫秒时间戳） */
    private Long dt;
}
