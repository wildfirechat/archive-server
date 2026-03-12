package cn.wildfirechat.archive.util;

import cn.wildfirechat.archive.dto.MessagePayload;
import cn.wildfirechat.sdk.messagecontent.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Base64;

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
            
            // 3. 使用 MessageContentFactory 解析为具体的 MessageContent 子类
            MessageContent messageContent = MessageContentFactory.decodeMessageContent(protoContent);
            
            // 4. 使用 encode() 获取 SDK 的 MessagePayload，然后转换为 DTO
            cn.wildfirechat.pojos.MessagePayload sdkPayload = messageContent.encode();
            return convertFromSdkPayload(sdkPayload, contentData, messageContent);
            
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
     * @param originalContentData 原始的 Base64 数据（用于未知类型）
     * @param messageContent 具体的 MessageContent 子类（用于获取类型特定字段）
     */
    private MessagePayload convertFromSdkPayload(cn.wildfirechat.pojos.MessagePayload sdkPayload, 
                                                  String originalContentData, 
                                                  MessageContent messageContent) {
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
        
        // 媒体相关字段（从 SDK MessagePayload 复制）
        payload.setRemoteMediaUrl(sdkPayload.getRemoteMediaUrl());
        
        // 根据具体消息类型处理特定字段（从具体子类获取）
        if (messageContent instanceof TextMessageContent) {
            TextMessageContent text = (TextMessageContent) messageContent;
            payload.setContent(text.getText());
            // QuoteInfo 可以放入 extra
            if (text.getQuoteInfo() != null) {
                JsonObject extraObj = new JsonObject();
                extraObj.add("quote", gson.toJsonTree(text.getQuoteInfo()));
                payload.setExtra(extraObj.toString());
            }
            
        } else if (messageContent instanceof ImageMessageContent) {
            ImageMessageContent image = (ImageMessageContent) messageContent;
            // 缩略图信息放入 extra
            if (image.getThumbnailBytes() != null) {
                JsonObject imageExtra = new JsonObject();
                imageExtra.addProperty("thumbnailSize", image.getThumbnailBytes().length);
                payload.setExtra(imageExtra.toString());
            }
            
        } else if (messageContent instanceof SoundMessageContent) {
            SoundMessageContent sound = (SoundMessageContent) messageContent;
            JsonObject soundExtra = new JsonObject();
            soundExtra.addProperty("duration", sound.getDuration());
            payload.setExtra(soundExtra.toString());
            
        } else if (messageContent instanceof FileMessageContent) {
            FileMessageContent file = (FileMessageContent) messageContent;
            JsonObject fileExtra = new JsonObject();
            fileExtra.addProperty("name", file.getName());
            fileExtra.addProperty("size", file.getSize());
            payload.setExtra(fileExtra.toString());
            
        } else if (messageContent instanceof VideoMessageContent) {
            VideoMessageContent video = (VideoMessageContent) messageContent;
            JsonObject videoExtra = new JsonObject();
            videoExtra.addProperty("duration", video.getDuration());
            if (video.getThumbnailBytes() != null) {
                videoExtra.addProperty("thumbnailSize", video.getThumbnailBytes().length);
            }
            payload.setExtra(videoExtra.toString());
            
        } else if (messageContent instanceof LocationMessageContent) {
            LocationMessageContent location = (LocationMessageContent) messageContent;
            JsonObject locationExtra = new JsonObject();
            locationExtra.addProperty("longitude", location.getLongitude());
            locationExtra.addProperty("latitude", location.getLatitude());
            if (location.getThumbnailByte() != null) {
                locationExtra.addProperty("thumbnailSize", location.getThumbnailByte().length);
            }
            payload.setContent(location.getTitle()); // 内容使用标题
            payload.setExtra(locationExtra.toString());
            
        } else if (messageContent instanceof LinkMessageContent) {
            LinkMessageContent link = (LinkMessageContent) messageContent;
            JsonObject linkExtra = new JsonObject();
            linkExtra.addProperty("url", link.getUrl());
            if (link.getThumbnailUrl() != null) {
                linkExtra.addProperty("thumbnailUrl", link.getThumbnailUrl());
            }
            if (link.getContentDigest() != null) {
                linkExtra.addProperty("digest", link.getContentDigest());
            }
            payload.setContent(link.getTitle());
            payload.setExtra(linkExtra.toString());
            
        } else if (messageContent instanceof CardMessageContent) {
            CardMessageContent card = (CardMessageContent) messageContent;
            JsonObject cardExtra = new JsonObject();
            cardExtra.addProperty("target", card.getTarget());
            cardExtra.addProperty("name", card.getName());
            cardExtra.addProperty("displayName", card.getDisplayName());
            cardExtra.addProperty("type", card.getType());
            if (card.getPortrait() != null) {
                cardExtra.addProperty("portrait", card.getPortrait());
            }
            if (card.getFrom() != null) {
                cardExtra.addProperty("from", card.getFrom());
            }
            payload.setExtra(cardExtra.toString());
            
        } else if (messageContent instanceof ArticlesMessageContent) {
            ArticlesMessageContent articles = (ArticlesMessageContent) messageContent;
            // 文章列表作为 extra（直接字段访问）
            JsonObject articlesExtra = new JsonObject();
            if (articles.topArticle != null) {
                articlesExtra.add("topArticle", gson.toJsonTree(articles.topArticle));
            }
            if (articles.subArticles != null && !articles.subArticles.isEmpty()) {
                articlesExtra.add("subArticles", gson.toJsonTree(articles.subArticles));
            }
            payload.setExtra(articlesExtra.toString());
            
        } else if (messageContent instanceof RichNotificationMessageContent) {
            // 富文本通知：SDK 中没有 getter，使用 SDK MessagePayload 的内容
            // extra 字段可能包含更多信息，尝试解析
            payload.setContent(sdkPayload.getContent());
            
        } else if (messageContent instanceof CallStartMessageContent) {
            CallStartMessageContent call = (CallStartMessageContent) messageContent;
            JsonObject callExtra = new JsonObject();
            callExtra.addProperty("callId", call.getCallId());
            callExtra.addProperty("status", call.getStatus());
            callExtra.addProperty("type", call.getType());
            callExtra.addProperty("audioOnly", call.isAudioOnly());
            if (call.getTargetIds() != null && !call.getTargetIds().isEmpty()) {
                callExtra.add("targetIds", gson.toJsonTree(call.getTargetIds()));
            }
            if (call.getPin() != null) {
                callExtra.addProperty("pin", call.getPin());
            }
            if (call.getConnectTime() > 0) {
                callExtra.addProperty("connectTime", call.getConnectTime());
            }
            if (call.getEndTime() > 0) {
                callExtra.addProperty("endTime", call.getEndTime());
            }
            payload.setExtra(callExtra.toString());
            
        } else if (messageContent instanceof RecallMessageContent) {
            RecallMessageContent recall = (RecallMessageContent) messageContent;
            payload.setContent(String.valueOf(recall.getMessageId()));
            JsonObject recallExtra = new JsonObject();
            recallExtra.addProperty("operatorId", recall.getOperatorId());
            recallExtra.addProperty("originalSender", recall.getOriginalSender());
            recallExtra.addProperty("originalContentType", recall.getOriginalContentType());
            recallExtra.addProperty("originalMessageTimestamp", recall.getOriginalMessageTimestamp());
            payload.setExtra(recallExtra.toString());
            
        } else if (messageContent instanceof DeleteMessageContent) {
            DeleteMessageContent delete = (DeleteMessageContent) messageContent;
            payload.setContent(String.valueOf(delete.getMessageId()));
            JsonObject deleteExtra = new JsonObject();
            deleteExtra.addProperty("operatorId", delete.getOperatorId());
            payload.setExtra(deleteExtra.toString());
            
        } else if (messageContent instanceof TipNotificationMessageContent) {
            TipNotificationMessageContent tip = (TipNotificationMessageContent) messageContent;
            payload.setContent(tip.getTip());
            
        } else if (messageContent instanceof TypingMessageContent) {
            TypingMessageContent typing = (TypingMessageContent) messageContent;
            payload.setSubType(typing.getTypingType());
            
        } else if (messageContent instanceof StreamTextGeneratingMessageContent) {
            // 流式文本生成中：SDK 中没有 getter，使用 SDK MessagePayload 的内容
            payload.setContent(sdkPayload.getContent());
            payload.setExtra(sdkPayload.getExtra()); // streamId 可能在 extra 中
            
        } else if (messageContent instanceof StreamTextGeneratedMessageContent) {
            // 流式文本生成完成：SDK 中没有 getter，使用 SDK MessagePayload 的内容
            payload.setContent(sdkPayload.getContent());
            payload.setExtra(sdkPayload.getExtra()); // streamId 可能在 extra 中
            
        } else if (messageContent instanceof UnknownMessageContent) {
            // 未知消息类型：保留原始数据
            payload.setBinaryContent(originalContentData);
        }
        
        // 其他未显式处理的消息类型，保留原始数据
        if (payload.getBinaryContent() == null && isUnknownType(messageContent)) {
            payload.setBinaryContent(originalContentData);
        }
        
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
