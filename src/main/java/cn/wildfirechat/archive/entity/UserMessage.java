package cn.wildfirechat.archive.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户消息关联实体 - 对应 t_user_messages_* 表
 */
@Data
public class UserMessage {
    private Long mid;
    private String uid;
    private LocalDateTime dt;
    private Integer line;
    private Integer type;
    private String target;
    private Integer contType;
}
