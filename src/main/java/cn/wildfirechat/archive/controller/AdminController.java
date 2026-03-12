package cn.wildfirechat.archive.controller;

import cn.wildfirechat.archive.dto.Result;
import cn.wildfirechat.archive.service.ArchiveService;
import cn.wildfirechat.archive.service.impl.ArchiveServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理接口
 * 
 * 注意：同步节点只能启动一个，通过配置 archive.task.sync-enabled 控制
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    
    private final ArchiveService archiveService;
    
    /**
     * 获取归档状态
     * 
     * 返回示例：
     * {
     *   "code": 0,
     *   "data": {
     *     "nodeId": "server01-a1b2c3d4",
     *     "nodeType": "SYNC",        // SYNC 或 QUERY
     *     "running": true,           // 同步任务是否运行中
     *     "shuttingDown": false,
     *     "metrics": { ... }
     *   }
     * }
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> getStatus() {
        if (archiveService instanceof ArchiveServiceImpl) {
            ArchiveServiceImpl impl = (ArchiveServiceImpl) archiveService;
            return Result.success(impl.getStatus());
        }
        
        Map<String, Object> data = new HashMap<>();
        data.put("running", archiveService.isRunning());
        return Result.success(data);
    }
    
    /**
     * 获取同步进度
     * 
     * 注意：实际进度需要查询 archive_progress 表
     */
    @GetMapping("/progress")
    public Result<Map<String, Object>> getProgress() {
        Map<String, Object> data = new HashMap<>();
        data.put("message", "Query progress from archive_progress table in ClickHouse");
        data.put("sql", "SELECT * FROM archive_progress FINAL ORDER BY table_name");
        return Result.success(data);
    }
    
    /**
     * 获取当前运行状态
     */
    @GetMapping("/running")
    public Result<Map<String, Object>> getRunningStatus() {
        Map<String, Object> data = new HashMap<>();
        data.put("running", archiveService.isRunning());
        return Result.success(data);
    }
    
    /**
     * 获取部署说明
     */
    @GetMapping("/deployment")
    public Result<Map<String, Object>> getDeploymentGuide() {
        Map<String, Object> data = new HashMap<>();
        
        data.put("warning", "只能启动一个同步节点（sync-enabled=true）！");
        data.put("syncNode", new HashMap<String, Object>() {{
            put("count", 1);
            put("config", "archive.task.sync-enabled=true");
            put("function", "从源数据库同步消息到 ClickHouse");
        }});
        data.put("queryNodes", new HashMap<String, Object>() {{
            put("count", "多个");
            put("config", "archive.task.sync-enabled=false");
            put("function", "提供消息查询 API");
        }});
        
        return Result.success(data);
    }
}
