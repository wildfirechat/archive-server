package cn.wildfirechat.archive.service;

import cn.wildfirechat.archive.dto.FetchRequest;
import cn.wildfirechat.archive.dto.MessageFetchResponse;
import cn.wildfirechat.archive.dto.SearchRequest;

/**
 * 消息查询服务接口
 */
public interface MessageQueryService {
    
    /**
     * 读取消息列表
     * @param userId 用户ID
     * @param request 请求参数
     * @return 消息列表响应
     */
    MessageFetchResponse fetchMessages(String userId, FetchRequest request);
    
    /**
     * 搜索消息
     * @param userId 用户ID
     * @param request 搜索请求参数
     * @return 消息列表响应
     */
    MessageFetchResponse searchMessages(String userId, SearchRequest request);
}
