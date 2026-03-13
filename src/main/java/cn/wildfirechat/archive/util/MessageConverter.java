package cn.wildfirechat.archive.util;

import cn.wildfirechat.archive.dto.MessagePayload;
import cn.wildfirechat.sdk.messagecontent.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Base64;
import java.util.List;

/**
 * 消息内容转换器
 * 
 * 将 contentData(Base64编码的二进制) 转换为 MessagePayload
 * 
 * 转换流程：
 * contentData(Base64) → byte[] → WFCMessage.MessageContent → MessageContent → MessagePayload
 * 
 * 实际实现：使用 MessageContent.encode() 获取 SDK 的 MessagePayload，然后转换为 DTO
 */
@Slf4j
@Component
public class MessageConverter {
    
    private final Gson gson = new Gson();
    
    /**
     * 初始化时注册自定义消息内容（如果有）
     */
    @PostConstruct
    public void init() {
        // MessageContentFactory 会自动扫描 cn.wildfirechat.sdk.messagecontent 包下的所有 MessageContent 子类
        // 如果需要注册自定义消息内容，可以在这里调用 MessageContentFactory.registerCustomMessageContent()
        log.info("MessageConverter initialized");
    }
    
    /**
     * 将 contentData 转换为 MessagePayload
     * 
     * @param contentData Base64编码的消息内容
     * @return MessagePayload 解析后的消息内容，解析失败返回包含原始数据的 Payload
     */
    public MessagePayload convertToPayload(String contentData) {
        if (contentData == null || contentData.isEmpty()) {
            return null;
        }
        
        try {
            // 1. Base64 解码
            byte[] data = Base64.getDecoder().decode(contentData);
            
            // 2. 解析 protobuf 为 WFCMessage.MessageContent
            cn.wildfirechat.proto.WFCMessage.MessageContent protoContent = 
                cn.wildfirechat.proto.WFCMessage.MessageContent.parseFrom(data);

            cn.wildfirechat.pojos.MessagePayload sdkPayload = cn.wildfirechat.pojos.MessagePayload.fromProtoMessageContent(protoContent);
            return convertFromSdkPayload(sdkPayload);
        } catch (Exception e) {
            log.warn("Failed to parse message content: {}", e.getMessage());
            // 解析失败时返回包含原始数据的 Payload
            MessagePayload payload = new MessagePayload();
            payload.setBinaryContent(contentData);
            return payload;
        }
    }
    
    /**
     * 将 SDK 的 MessagePayload 转换为 DTO
     * 
     * @param sdkPayload SDK 的 MessagePayload
     */
    private MessagePayload convertFromSdkPayload(cn.wildfirechat.pojos.MessagePayload sdkPayload) {
        MessagePayload payload = new MessagePayload();
        
        // 基础字段（从 SDK MessagePayload 复制）
        payload.setType(sdkPayload.getType());
        payload.setSearchableContent(sdkPayload.getSearchableContent());
        payload.setPushContent(sdkPayload.getPushContent());
        payload.setPushData(sdkPayload.getPushData());
        payload.setContent(sdkPayload.getContent());
        payload.setMentionedType(sdkPayload.getMentionedType());
        payload.setMentionedTargets(sdkPayload.getMentionedTarget());
        payload.setExtra(sdkPayload.getExtra());
        payload.setRemoteMediaUrl(sdkPayload.getRemoteMediaUrl());
        payload.setBinaryContent(sdkPayload.getBase64edData());
        return payload;
    }
    
    /**
     * 判断是否为未知类型（未在上面显式处理的类型）
     */
    private boolean isUnknownType(MessageContent content) {
        return !(content instanceof TextMessageContent
            || content instanceof ImageMessageContent
            || content instanceof SoundMessageContent
            || content instanceof FileMessageContent
            || content instanceof VideoMessageContent
            || content instanceof LocationMessageContent
            || content instanceof LinkMessageContent
            || content instanceof CardMessageContent
            || content instanceof ArticlesMessageContent
            || content instanceof RichNotificationMessageContent
            || content instanceof CallStartMessageContent
            || content instanceof RecallMessageContent
            || content instanceof DeleteMessageContent
            || content instanceof TipNotificationMessageContent
            || content instanceof TypingMessageContent
            || content instanceof StreamTextGeneratingMessageContent
            || content instanceof StreamTextGeneratedMessageContent
            || content instanceof UnknownMessageContent);
    }
}
