package cn.wildfirechat.archive.service.impl;

import cn.wildfirechat.archive.dto.ArchivedMessageDTO;
import cn.wildfirechat.archive.dto.FetchRequest;
import cn.wildfirechat.archive.dto.MessageFetchResponse;
import cn.wildfirechat.archive.dto.SearchRequest;
import cn.wildfirechat.archive.entity.ArchivedMessage;
import cn.wildfirechat.archive.repository.clickhouse.ArchiveRepository;
import cn.wildfirechat.archive.service.MessageQueryService;
import cn.wildfirechat.archive.util.MessageConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageQueryServiceImpl implements MessageQueryService {
    
    private final ArchiveRepository archiveRepository;
    private final MessageConverter messageConverter;
    
    @Override
    public MessageFetchResponse fetchMessages(String userId, FetchRequest request) {
        List<ArchivedMessage> messages = archiveRepository.fetchMessages(userId, request);
        return buildResponse(messages, request.getLimit());
    }
    
    @Override
    public MessageFetchResponse searchMessages(String userId, SearchRequest request) {
        List<ArchivedMessage> messages = archiveRepository.searchMessages(
            userId, 
            request.getKeyword(), 
            request
        );
        int limit = request.getLimit() != null ? request.getLimit() : 20;
        return buildResponse(messages, limit);
    }
    
    /**
     * 构建查询响应
     * 将 ArchivedMessage 实体转换为 ArchivedMessageDTO（包含解析后的 payload）
     */
    private MessageFetchResponse buildResponse(List<ArchivedMessage> messages, int limit) {
        MessageFetchResponse response = new MessageFetchResponse();
        
        // 转换为 DTO 列表（解析 contentData 为 MessagePayload）
        List<ArchivedMessageDTO> dtoList = messages.stream()
            .map(this::convertToDTO)
            .collect(Collectors.toList());
        
        response.setMessages(dtoList);
        response.setHasMore(messages.size() >= limit);
        
        if (!messages.isEmpty()) {
            response.setNextStartMid(messages.get(messages.size() - 1).getMid());
        }
        
        return response;
    }
    
    /**
     * 将 ArchivedMessage 实体转换为 ArchivedMessageDTO
     */
    private ArchivedMessageDTO convertToDTO(ArchivedMessage am) {
        ArchivedMessageDTO dto = new ArchivedMessageDTO();
        dto.setMid(am.getMid());
        dto.setSenderId(am.getSenderId());
        dto.setConvType(am.getConvType());
        dto.setConvTarget(am.getConvTarget());
        dto.setConvLine(am.getConvLine());
        dto.setContentType(am.getContentType());
        dto.setUserId(am.getUserId());
        dto.setMessageDt(am.getMessageDt());
        dto.setSearchableKey(am.getSearchableKey());
        
        // 解析 contentData 为 MessagePayload
        dto.setPayload(messageConverter.convertToPayload(am.getContentData()));
        
        return dto;
    }
}
