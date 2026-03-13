package cn.wildfirechat.archive.dto;

import lombok.Data;

import java.util.List;

/**
 * 消息内容 Payload - 返回给客户端的消息内容
 * 
 * 由 contentData(Base64编码的二进制) 解析而来
 * 解析流程：contentData → byte[] → WFCMessage.MessageContent → MessageContent → MessagePayload
 */
@Data
public class MessagePayload {
    
    /**
     * 消息类型
     */
    private Integer type;
    
    /**
     * 可搜索内容
     */
    private String searchableContent;
    
    /**
     * 推送内容
     */
    private String pushContent;
    
    /**
     * 推送数据
     */
    private String pushData;
    
    /**
     * 消息内容（文本消息的实际内容）
     */
    private String content;
    
    /**
     * 二进制内容（Base64编码，用于无法解析的情况）
     */
    private String binaryContent;
    
    /**
     * @类型：0-不提醒，1-所有人，2-部分人
     */
    private Integer mentionedType;
    
    /**
     * @的目标用户列表
     */
    private List<String> mentionedTargets;
    
    /**
     * 媒体消息相关字段（图片、语音、文件、视频等）
     */
    private String remoteMediaUrl;

    /**
     * 附加信息
     */
    private String extra;
}
