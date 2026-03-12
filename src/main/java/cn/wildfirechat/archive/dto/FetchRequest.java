package cn.wildfirechat.archive.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@Data
public class FetchRequest {
    
    /**
     * 会话类型: 0单聊/1群聊/2聊天室/3频道
     */
    private Integer convType;
    
    /**
     * 会话目标
     */
    private String convTarget;
    
    /**
     * 会话线路，默认0
     */
    private Integer convLine = 0;
    
    /**
     * 消息内容类型
     */
    private Integer contentType;
    
    /**
     * 起始消息ID
     */
    private Long startMid;
    
    /**
     * true=向前查(更早)，false=向后查(更新)
     */
    private Boolean before = true;
    
    /**
     * 条数，默认20，最大100
     */
    @Min(1)
    @Max(100)
    private Integer limit = 20;
}
