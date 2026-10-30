package cn.wildfirechat.archive.service.impl;

import cn.wildfirechat.archive.dto.ArchivedMessageDTO;
import cn.wildfirechat.archive.dto.FetchRequest;
import cn.wildfirechat.archive.dto.MessageFetchResponse;
import cn.wildfirechat.archive.dto.SearchRequest;
import cn.wildfirechat.archive.entity.ArchivedGroupMessage;
import cn.wildfirechat.archive.entity.ArchivedMessage;
import cn.wildfirechat.archive.repository.clickhouse.ArchiveRepository;
import cn.wildfirechat.archive.service.MessageQueryService;
import cn.wildfirechat.archive.util.MessageConverter;
import cn.wildfirechat.common.ErrorCode;
import cn.wildfirechat.pojos.PojoGroupMember;
import cn.wildfirechat.proto.ProtoConstants;
import cn.wildfirechat.sdk.GroupAdmin;
import cn.wildfirechat.sdk.model.IMResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageQueryServiceImpl implements MessageQueryService {

    private final ArchiveRepository archiveRepository;
    private final MessageConverter messageConverter;

    // ==================== 普通消息查询 ====================

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
     * 构建普通消息查询响应
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
     * 将普通消息实体转换为 DTO
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

    // ==================== 群组消息查询 ====================

    @Override
    public MessageFetchResponse fetchGroupMessages(String userId, FetchRequest request) {
        String gid = request.getConvTarget();
        if (gid == null || gid.isEmpty()) {
            throw new IllegalArgumentException("群组ID不能为空");
        }
        checkGroupMember(userId, gid);

        List<ArchivedGroupMessage> messages = archiveRepository.fetchGroupMessages(gid, request);
        return buildGroupResponse(messages, request.getLimit());
    }

    @Override
    public MessageFetchResponse searchGroupMessages(String userId, SearchRequest request) {
        String gid = request.getConvTarget();
        String keyword = request.getKeyword();

        if (keyword == null || keyword.isEmpty()) {
            throw new IllegalArgumentException("搜索关键字不能为空");
        }
        // 不支持“搜索所有群组”：那会把任意用户拉不到的其他群消息一并返回。
        // 必须指定 gid，并校验当前用户是该群成员。
        if (gid == null || gid.isEmpty()) {
            throw new SecurityException("必须指定群组ID");
        }
        checkGroupMember(userId, gid);

        List<ArchivedGroupMessage> messages = archiveRepository.searchGroupMessages(gid, keyword, request);

        int limit = request.getLimit() != null ? request.getLimit() : 20;
        return buildGroupResponse(messages, limit);
    }

    /**
     * 群组消息属于群成员，任何登录用户都不能凭 gid 直接读别的群。
     * 成员关系以 IM 服务为准；IM 不可用或返回异常时拒绝访问（fail closed）。
     */
    private void checkGroupMember(String userId, String gid) {
        if (userId == null || userId.isEmpty()) {
            throw new SecurityException("未登录");
        }
        IMResult<PojoGroupMember> result;
        try {
            result = GroupAdmin.getGroupMember(gid, userId);
        } catch (Exception e) {
            throw new SecurityException("无法校验群成员身份", e);
        }
        if (result == null || result.getErrorCode() != ErrorCode.ERROR_CODE_SUCCESS || result.getResult() == null) {
            throw new SecurityException("无权访问该群组");
        }
        if (result.getResult().getType() == 4) {
            throw new SecurityException("无权访问该群组");
        }
    }

    /**
     * 构建群组消息查询响应
     */
    private MessageFetchResponse buildGroupResponse(List<ArchivedGroupMessage> messages, int limit) {
        MessageFetchResponse response = new MessageFetchResponse();

        // 转换为 DTO 列表（复用 ArchivedMessageDTO）
        List<ArchivedMessageDTO> dtoList = messages.stream()
            .map(this::convertGroupMessageToDTO)
            .collect(Collectors.toList());

        response.setMessages(dtoList);
        response.setHasMore(messages.size() >= limit);

        if (!messages.isEmpty()) {
            response.setNextStartMid(messages.get(messages.size() - 1).getMid());
        }

        return response;
    }

    /**
     * 将群组消息实体转换为 DTO
     * 群组消息的 convType=1（群组类型），convTarget=gid
     */
    private ArchivedMessageDTO convertGroupMessageToDTO(ArchivedGroupMessage am) {
        ArchivedMessageDTO dto = new ArchivedMessageDTO();
        dto.setMid(am.getMid());
        dto.setSenderId(am.getSenderId());
        // 群组消息的会话类型为 1（群组）
        dto.setConvType(ProtoConstants.ConversationType.ConversationType_Group);
        // 群组消息的会话目标为群组ID
        dto.setConvTarget(am.getGid());
        dto.setConvLine(am.getConvLine());
        dto.setContentType(am.getContentType());
        // 设置用户ID（与发送者相同，保持与用户消息格式一致）
        dto.setUserId(am.getSenderId());
        dto.setSearchableKey(am.getSearchableKey());
        dto.setMessageDt(am.getMessageDt());

        // 解析 contentData 为 MessagePayload
        dto.setPayload(messageConverter.convertToPayload(am.getContentData()));

        return dto;
    }
}
