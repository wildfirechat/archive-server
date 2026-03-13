package cn.wildfirechat.archive.controller;

import cn.wildfirechat.archive.dto.FetchRequest;
import cn.wildfirechat.archive.dto.MessageFetchResponse;
import cn.wildfirechat.archive.dto.Result;
import cn.wildfirechat.archive.dto.SearchRequest;
import cn.wildfirechat.archive.filter.AuthFilter;
import cn.wildfirechat.archive.service.MessageQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

/**
 * 群组消息归档查询接口
 * 
 * 用于查询群组历史消息（超级群组消息）
 * 
 * 说明：复用普通消息的 FetchRequest 和 MessageFetchResponse，
 * 通过 convType=1 和 convTarget=gid 来查询群组消息
 */
@Slf4j
@RestController
@RequestMapping("/api/group-messages")
@RequiredArgsConstructor
@Validated
public class GroupMessageController {
    
    private final MessageQueryService messageQueryService;

    /**
     * 读取群组消息列表
     * 
     * 说明：复用普通消息的 FetchRequest，convTarget 为群组ID
     * 
     * @param request 查询请求
     * @param httpRequest HTTP请求（用于获取当前用户ID）
     * @return 群组消息列表
     */
    @PostMapping("/fetch")
    public Result<MessageFetchResponse> fetchGroupMessages(
            @RequestBody @Validated FetchRequest request,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(AuthFilter.USER_ID_KEY);
        log.debug("Fetch group messages for user: {}, gid: {}, request: {}", 
            userId, request.getConvTarget(), request);
        
        // 调用专门的群组消息查询方法（查询 group_message_archive 表）
        MessageFetchResponse response = messageQueryService.fetchGroupMessages(request);
        return Result.success(response);
    }
    
    /**
     * 搜索群组消息
     * 
     * 说明：复用普通消息的 SearchRequest，convTarget 为群组ID（可选）
     * 
     * @param request 搜索请求
     * @param httpRequest HTTP请求（用于获取当前用户ID）
     * @return 搜索结果的群组消息列表
     */
    @PostMapping("/search")
    public Result<MessageFetchResponse> searchGroupMessages(
            @RequestBody @Validated SearchRequest request,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(AuthFilter.USER_ID_KEY);
        log.debug("Search group messages for user: {}, gid: {}, keyword: {}", 
            userId, request.getConvTarget(), request.getKeyword());
        
        // 调用专门的群组消息查询方法（查询 group_message_archive 表）
        MessageFetchResponse response = messageQueryService.searchGroupMessages(request);
        return Result.success(response);
    }
}
