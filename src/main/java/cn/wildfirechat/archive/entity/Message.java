package cn.wildfirechat.archive.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息实体 - 对应 t_messages_* 表
 */
@Data
public class Message {
    private Long id;
    private Long mid;
    private String from;
    private Integer type;
    private String target;
    private Integer line;
    private byte[] data;
    private String searchableKey;
    private LocalDateTime dt;
    private Integer contentType;
    private String to;
}
