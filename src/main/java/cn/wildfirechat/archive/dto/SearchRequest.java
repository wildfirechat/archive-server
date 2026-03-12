package cn.wildfirechat.archive.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.NotBlank;

@Data
@EqualsAndHashCode(callSuper = true)
public class SearchRequest extends FetchRequest {
    
    /**
     * 搜索关键字
     */
    @NotBlank(message = "keyword is required")
    private String keyword;
}
