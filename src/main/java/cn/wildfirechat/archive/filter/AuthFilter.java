package cn.wildfirechat.archive.filter;

import cn.wildfirechat.archive.config.IMConfig;
import cn.wildfirechat.archive.dto.Result;
import cn.wildfirechat.common.ErrorCode;
import cn.wildfirechat.pojos.OutputApplicationUserInfo;
import cn.wildfirechat.sdk.UserAdmin;
import cn.wildfirechat.sdk.model.IMResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthFilter implements Filter {
    
    public static final String USER_ID_KEY = "userId";
    
    private final IMConfig imConfig;
    private final ObjectMapper objectMapper;
    
    @PostConstruct
    public void init() {
        // 初始化IM SDK
        cn.wildfirechat.sdk.utilities.AdminHttpUtils.init(imConfig.getAdminUrl(), imConfig.getSecret());
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        // 处理 OPTIONS 请求
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        
        String requestURI = httpRequest.getRequestURI();
        log.debug("AuthFilter processing request: {}", requestURI);
        
        // 从 header 获取 authCode
        String authCode = httpRequest.getHeader("authCode");
        if (authCode == null || authCode.isEmpty()) {
            log.warn("Missing authCode in request: {}", requestURI);
            writeErrorResponse(httpResponse, 401, "authCode is required");
            return;
        }
        
        // 验证 authCode
        String userId = validateAuthCode(authCode);
        if (userId == null) {
            log.warn("Invalid authCode: {}", authCode);
            writeErrorResponse(httpResponse, 401, "invalid authCode");
            return;
        }
        
        // 将 userId 设置到 request 属性中
        httpRequest.setAttribute(USER_ID_KEY, userId);
        log.debug("Auth success, userId: {}", userId);
        
        chain.doFilter(request, response);
    }
    
    private String validateAuthCode(String authCode) {
        log.debug("Validate authCode with IM service");
        try {
            IMResult<OutputApplicationUserInfo> imResult = UserAdmin.applicationGetUserInfo(authCode);
            if (imResult != null && imResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                return imResult.getResult().getUserId();
            }
        } catch (Exception e) {
            log.error("Failed to validate authCode", e);
        }
        return null;
    }
    
    private void writeErrorResponse(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Result<?> result = Result.error(code, message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
