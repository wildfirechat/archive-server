package cn.wildfirechat.archive.controller;

import cn.wildfirechat.archive.dto.*;
import cn.wildfirechat.archive.filter.AuthFilter;
import cn.wildfirechat.archive.service.MessageQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;

@Slf4j
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
@Validated
public class MessageController {
    
    private final MessageQueryService messageQueryService;

    /**
     * 读取消息列表
     */
    @PostMapping("/fetch")
    public Result<MessageFetchResponse> fetchMessages(
            @RequestBody @Validated FetchRequest request,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(AuthFilter.USER_ID_KEY);
        log.debug("Fetch messages for user: {}, request: {}", userId, request);
        
        MessageFetchResponse response = messageQueryService.fetchMessages(userId, request);
        return Result.success(response);
    }
    
    /**
     * 搜索消息
     */
    @PostMapping("/search")
    public Result<MessageFetchResponse> searchMessages(
            @RequestBody @Validated SearchRequest request,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute(AuthFilter.USER_ID_KEY);
        log.debug("Search messages for user: {}, keyword: {}", userId, request.getKeyword());
        
        MessageFetchResponse response = messageQueryService.searchMessages(userId, request);
        return Result.success(response);
    }
}
