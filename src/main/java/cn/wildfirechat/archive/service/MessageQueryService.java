package cn.wildfirechat.archive.service;

import cn.wildfirechat.archive.dto.FetchRequest;
import cn.wildfirechat.archive.dto.MessageFetchResponse;
import cn.wildfirechat.archive.dto.SearchRequest;

/**
 * 消息查询服务接口
 * 
 * 说明：
 * - fetchMessages/searchMessages: 查询普通消息表（message_archive）
 * - fetchGroupMessages/searchGroupMessages: 查询群组消息表（group_message_archive）
 */
public interface MessageQueryService {
    
    /**
     * 读取普通消息列表
     * @param userId 用户ID
     * @param request 请求参数
     * @return 消息列表响应
     */
    MessageFetchResponse fetchMessages(String userId, FetchRequest request);
    
    /**
     * 搜索普通消息
     * @param userId 用户ID
     * @param request 搜索请求参数
     * @return 消息列表响应
     */
    MessageFetchResponse searchMessages(String userId, SearchRequest request);
    
    /**
     * 读取群组消息列表（超级群组）
     * @param request 请求参数（convTarget 为群组ID）
     * @return 消息列表响应
     */
    MessageFetchResponse fetchGroupMessages(FetchRequest request);
    
    /**
     * 搜索群组消息（超级群组）
     * @param request 搜索请求参数（keyword 必填，convTarget 可选）
     * @return 消息列表响应
     */
    MessageFetchResponse searchGroupMessages(SearchRequest request);
}
