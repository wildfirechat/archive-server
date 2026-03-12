package cn.wildfirechat.archive.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 归档消息宽表实体 - 对应 ClickHouse
 */
@Data
public class ArchivedMessage {
    private Long mid;
    private String senderId;
    private Integer convType;
    private String convTarget;
    private Integer convLine;
    private Integer contentType;
    private String contentData;
    private String searchableKey;
    private String userId;
    private LocalDateTime messageDt;
    private Integer userHash;  // 用于分区，userId.hashCode() % 128
}
