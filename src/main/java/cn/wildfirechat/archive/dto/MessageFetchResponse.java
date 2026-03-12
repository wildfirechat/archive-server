package cn.wildfirechat.archive.dto;

import lombok.Data;

import java.util.List;

/**
 * 消息查询响应
 * 
 * 返回给客户端的消息列表，messages 中的对象是 ArchivedMessageDTO
 * 已解析 contentData 为 MessagePayload
 */
@Data
public class MessageFetchResponse {
    
    /**
     * 消息列表（已解析 payload）
     */
    private List<ArchivedMessageDTO> messages;
    
    /**
     * 是否还有更多消息
     */
    private Boolean hasMore;
    
    /**
     * 下一页起始消息ID
     */
    private Long nextStartMid;
}
