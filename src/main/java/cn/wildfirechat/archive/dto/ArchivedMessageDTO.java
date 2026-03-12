package cn.wildfirechat.archive.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 归档消息 DTO - 返回给客户端的消息对象
 * 
 * 与 ArchivedMessage 实体类的区别：
 * - 将 contentData(Base64字符串) 替换为解析后的 payload(MessagePayload)
 */
@Data
public class ArchivedMessageDTO {
    
    /**
     * 消息唯一ID
     */
    private Long mid;
    
    /**
     * 发送者用户ID
     */
    private String senderId;
    
    /**
     * 会话类型：0单聊, 1群组, 2聊天室, 3频道
     */
    private Integer convType;
    
    /**
     * 会话目标（用户ID或群组ID）
     */
    private String convTarget;
    
    /**
     * 会话线路
     */
    private Integer convLine;
    
    /**
     * 消息内容类型
     */
    private Integer contentType;
    
    /**
     * 消息内容（解析后的 Payload）
     */
    private MessagePayload payload;
    
    /**
     * 可搜索文本（消息的文字内容摘要）
     */
    private String searchableKey;
    
    /**
     * 当前用户ID（用户视角）
     */
    private String userId;
    
    /**
     * 消息发送时间（UTC）
     */
    private LocalDateTime messageDt;
}
