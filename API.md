# 野火IM消息归档服务 - 客户端接口文档

## 概述

本文档供客户端（iOS/Android/Web）开发人员使用，描述如何调用消息归档服务的 API 查询历史消息。

## 基本信息

| 项目 | 说明 |
|------|------|
| 协议 | HTTP/HTTPS |
| 格式 | JSON |
| 编码 | UTF-8 |
| 认证方式 | Header 中携带 `authCode` |

## 认证说明

所有 API 请求（除健康检查外）都需要在 HTTP Header 中携带 `authCode`：

```http
POST /api/messages/fetch
Content-Type: application/json
authCode: xxxxxxxxxx

{...}
```

`authCode` 通过野火IM SDK 获取，用于验证用户身份。

## 通用响应格式

```json
{
  "code": 0,           // 错误码，0 表示成功
  "message": "success", // 错误描述
  "data": {...}        // 响应数据（可选）
}
```

### 错误码说明

| code | 说明 |
|------|------|
| 0 | 成功 |
| 401 | 认证失败（authCode 无效或过期）|
| 500 | 服务器内部错误 |

---

## 1. 获取消息列表

获取某个会话的历史消息列表。

### 请求

```http
POST /api/messages/fetch
```

### 请求参数

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| convType | int | 否 | - | 会话类型：0单聊, 1群组, 2聊天室, 3频道 |
| convTarget | string | 否 | - | 会话目标（用户ID或群组ID）|
| convLine | int | 否 | 0 | 会话线路 |
| contentType | int | 否 | - | 消息内容类型筛选 |
| startMid | long | 否 | - | 起始消息ID |
| before | boolean | 否 | true | true=查询更早的消息（向前翻页），false=查询更新的消息 |
| limit | int | 否 | 20 | 返回条数，最大100 |

### 请求示例

**查询某个群组的最新消息（第一页）：**

```json
{
  "convType": 1,
  "convTarget": "group123",
  "limit": 20
}
```

**翻页查询更早的消息：**

```json
{
  "convType": 1,
  "convTarget": "group123",
  "startMid": 1000,
  "before": true,
  "limit": 20
}
```

**查询更新的消息（用于轮询新消息）：**

```json
{
  "convType": 1,
  "convTarget": "group123",
  "startMid": 1000,
  "before": false,
  "limit": 20
}
```

### 响应参数

| 字段 | 类型 | 说明 |
|------|------|------|
| messages | array | 消息列表（见下方消息结构）|
| hasMore | boolean | 是否还有更多消息 |
| nextStartMid | long | 下一页起始消息ID（用于翻页）|

### 消息结构

| 字段 | 类型 | 说明 |
|------|------|------|
| mid | long | 消息唯一ID |
| senderId | string | 发送者用户ID |
| convType | int | 会话类型：0单聊, 1群组, 2聊天室, 3频道 |
| convTarget | string | 会话目标 |
| convLine | int | 会话线路 |
| contentType | int | 消息内容类型 |
| payload | object | 消息内容（解析后的 MessagePayload，见下方说明）|
| searchableKey | string | 可搜索文本（如消息的文字内容）|
| userId | string | 当前用户ID（用户视角）|
| messageDt | string | 消息发送时间（ISO 8601格式，UTC时间）|

#### MessagePayload 结构

| 字段 | 类型 | 说明 |
|------|------|------|
| type | int | 消息类型 |
| content | string | 消息文本内容（如文本消息的实际内容、位置标题、链接标题等）|
| searchableContent | string | 可搜索内容 |
| pushContent | string | 推送内容 |
| pushData | string | 推送数据 |
| mentionedType | int | @类型：0-不提醒，1-所有人，2-部分人 |
| mentionedTargets | array | @的目标用户ID列表 |
| remoteMediaUrl | string | 媒体文件远程URL（图片、语音、文件、视频等）|
| extra | string | 扩展字段（JSON格式，包含类型特定的额外信息）|
| binaryContent | string | 原始Base64数据（解析失败时保留）|
| subType | int | 子类型（如Typing消息的类型）|

**不同类型消息的 extra 字段内容：**

| 消息类型 | extra 字段内容 |
|----------|----------------|
| 文本消息 | `{"quote": {...}}` 引用信息（如有）|
| 图片消息 | `{"thumbnailSize": 1024}` 缩略图大小 |
| 语音消息 | `{"duration": 10}` 时长（秒）|
| 文件消息 | `{"name": "file.pdf", "size": 10240}` 文件名和大小 |
| 视频消息 | `{"duration": 60, "thumbnailSize": 2048}` 时长和缩略图大小 |
| 位置消息 | `{"longitude": 116.4, "latitude": 39.9, "thumbnailSize": 512}` 经纬度 |
| 链接消息 | `{"url": "https://...", "thumbnailUrl": "...", "digest": "..."}` 链接信息 |
| 名片消息 | `{"target": "userId", "name": "...", "displayName": "...", "type": 0, "portrait": "..."}` |
| 图文消息 | `{"topArticle": {...}, "subArticles": [...]}` 文章列表 |
| 音视频通话 | `{"callId": "...", "status": 0, "type": 0, "audioOnly": false, "targetIds": [...]}` |
| 撤回消息 | `{"operatorId": "...", "originalSender": "...", "originalContentType": 1}` |
| 删除消息 | `{"operatorId": "..."}` |

### 响应示例

**文本消息：**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "messages": [
      {
        "mid": 999,
        "senderId": "user1",
        "convType": 1,
        "convTarget": "group123",
        "convLine": 0,
        "contentType": 1,
        "payload": {
          "type": 1,
          "content": "Hello World",
          "searchableContent": "Hello World",
          "mentionedType": 0
        },
        "searchableKey": "Hello World",
        "userId": "user1",
        "messageDt": "2024-01-15T10:30:00"
      },
      {
        "mid": 998,
        "senderId": "user2",
        "convType": 1,
        "convTarget": "group123",
        "convLine": 0,
        "contentType": 1,
        "payload": {
          "type": 1,
          "content": "你好",
          "searchableContent": "你好",
          "mentionedType": 0
        },
        "searchableKey": "你好",
        "userId": "user2",
        "messageDt": "2024-01-15T10:29:30"
      }
    ],
    "hasMore": true,
    "nextStartMid": 998
  }
}
```

**图片消息：**
```json
{
  "mid": 997,
  "senderId": "user1",
  "convType": 1,
  "convTarget": "group123",
  "contentType": 2,
  "payload": {
    "type": 2,
    "remoteMediaUrl": "https://example.com/image.jpg",
    "extra": "{\"thumbnailSize\": 2048}"
  },
  "searchableKey": "[图片]",
  "messageDt": "2024-01-15T10:29:00"
}
```

**语音消息：**
```json
{
  "mid": 996,
  "senderId": "user1",
  "convType": 1,
  "convTarget": "group123",
  "contentType": 3,
  "payload": {
    "type": 3,
    "remoteMediaUrl": "https://example.com/audio.amr",
    "extra": "{\"duration\": 10}"
  },
  "searchableKey": "[语音]",
  "messageDt": "2024-01-15T10:28:00"
}
```

**位置消息：**
```json
{
  "mid": 995,
  "senderId": "user1",
  "convType": 1,
  "convTarget": "group123",
  "contentType": 6,
  "payload": {
    "type": 6,
    "content": "北京天安门",
    "searchableContent": "北京天安门",
    "extra": "{\"longitude\": 116.397428, \"latitude\": 39.90923}"
  },
  "searchableKey": "北京天安门",
  "messageDt": "2024-01-15T10:27:00"
}
```

### 客户端翻页逻辑

```
第一页请求：
  不传递 startMid，获取最新消息
  
下一页（更早的消息）：
  使用上一页返回的 nextStartMid
  before = true
  
轮询新消息：
  使用已知的最大 mid 作为 startMid
  before = false
```

---

## 2. 搜索消息

根据关键字搜索消息内容。

### 请求

```http
POST /api/messages/search
```

### 请求参数

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| keyword | string | **是** | - | 搜索关键字（长度建议 >= 3）|
| convType | int | 否 | - | 会话类型筛选 |
| convTarget | string | 否 | - | 会话目标筛选 |
| convLine | int | 否 | - | 会话线路筛选 |
| contentType | int | 否 | - | 内容类型筛选 |
| startMid | long | 否 | - | 起始消息ID |
| before | boolean | 否 | true | 翻页方向 |
| limit | int | 否 | 20 | 返回条数，最大100 |

### 请求示例

**搜索包含 "项目" 的消息：**

```json
{
  "keyword": "项目",
  "limit": 20
}
```

**在特定群组中搜索：**

```json
{
  "keyword": "项目进度",
  "convType": 1,
  "convTarget": "group123",
  "limit": 20
}
```

### 响应参数

与 `/api/messages/fetch` 相同。

### 响应示例

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "messages": [
      {
        "mid": 888,
        "senderId": "user3",
        "convType": 1,
        "convTarget": "group123",
        "contentType": 1,
        "payload": {
          "type": 1,
          "content": "项目进度正常",
          "searchableContent": "项目进度正常"
        },
        "searchableKey": "项目进度正常",
        "messageDt": "2024-01-15T09:00:00"
      }
    ],
    "hasMore": false,
    "nextStartMid": 888
  }
}
```

### 搜索注意事项

1. **关键字长度**：建议 >= 3 个字符以获得最佳性能
2. **模糊匹配**：支持子串匹配，如搜索 "项目" 可以匹配 "项目进度"
3. **性能**：搜索使用 n-gram 布隆过滤器索引，响应时间 < 100ms

---

## 3. 客户端代码示例

### Android (Java)

```java
public class ArchiveApiClient {
    private static final String BASE_URL = "https://archive.example.com";
    private OkHttpClient client = new OkHttpClient();
    private Gson gson = new Gson();
    
    // 获取消息列表
    public void fetchMessages(String authCode, int convType, String convTarget, 
                              Long startMid, boolean before, int limit,
                              Callback<MessageResponse> callback) {
        
        FetchRequest request = new FetchRequest();
        request.setConvType(convType);
        request.setConvTarget(convTarget);
        request.setStartMid(startMid);
        request.setBefore(before);
        request.setLimit(limit);
        
        RequestBody body = RequestBody.create(
            MediaType.parse("application/json"), 
            gson.toJson(request)
        );
        
        Request httpRequest = new Request.Builder()
            .url(BASE_URL + "/api/messages/fetch")
            .header("authCode", authCode)
            .post(body)
            .build();
            
        client.newCall(httpRequest).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String json = response.body().string();
                MessageResponse result = gson.fromJson(json, MessageResponse.class);
                callback.onSuccess(result);
            }
            
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }
        });
    }
    
    // 搜索消息
    public void searchMessages(String authCode, String keyword, int limit,
                               Callback<MessageResponse> callback) {
        
        SearchRequest request = new SearchRequest();
        request.setKeyword(keyword);
        request.setLimit(limit);
        
        // ... 类似 fetchMessages
    }
}

// 数据类
public class FetchRequest {
    private Integer convType;
    private String convTarget;
    private Long startMid;
    private Boolean before;
    private Integer limit;
    // getters/setters
}

public class MessagePayload {
    private int type;
    private String content;
    private String searchableContent;
    private String pushContent;
    private String pushData;
    private Integer mentionedType;
    private List<String> mentionedTargets;
    private String remoteMediaUrl;
    private String extra;
    private String binaryContent;
    private Integer subType;
    // getters/setters
}

public class ArchivedMessage {
    private long mid;
    private String senderId;
    private int convType;
    private String convTarget;
    private int convLine;
    private int contentType;
    private MessagePayload payload;
    private String searchableKey;
    private String userId;
    private String messageDt;
    // getters/setters
}

public class MessageResponse {
    private int code;
    private String message;
    private MessageListData data;
    // getters/setters
}

public class MessageListData {
    private List<ArchivedMessage> messages;
    private boolean hasMore;
    private long nextStartMid;
    // getters/setters
}
```

### iOS (Swift)

```swift
import Alamofire

class ArchiveApiClient {
    static let shared = ArchiveApiClient()
    private let baseURL = "https://archive.example.com"
    
    // 获取消息列表
    func fetchMessages(authCode: String, 
                       convType: Int?, 
                       convTarget: String?,
                       startMid: Int64?,
                       before: Bool = true,
                       limit: Int = 20,
                       completion: @escaping (Result<MessageResponse, Error>) -> Void) {
        
        let url = "\(baseURL)/api/messages/fetch"
        let headers: HTTPHeaders = ["authCode": authCode]
        let parameters: [String: Any] = [
            "convType": convType,
            "convTarget": convTarget,
            "startMid": startMid,
            "before": before,
            "limit": limit
        ].compactMapValues { $0 }
        
        AF.request(url, method: .post, parameters: parameters, 
                   encoding: JSONEncoding.default, headers: headers)
          .responseDecodable(of: MessageResponse.self) { response in
              completion(response.result.mapError { $0 as Error })
          }
    }
    
    // 搜索消息
    func searchMessages(authCode: String,
                        keyword: String,
                        limit: Int = 20,
                        completion: @escaping (Result<MessageResponse, Error>) -> Void) {
        
        let url = "\(baseURL)/api/messages/search"
        let headers: HTTPHeaders = ["authCode": authCode]
        let parameters: [String: Any] = [
            "keyword": keyword,
            "limit": limit
        ]
        
        AF.request(url, method: .post, parameters: parameters,
                   encoding: JSONEncoding.default, headers: headers)
          .responseDecodable(of: MessageResponse.self) { response in
              completion(response.result.mapError { $0 as Error })
          }
    }
}

// 数据模型
struct MessageResponse: Codable {
    let code: Int
    let message: String
    let data: MessageListData?
}

struct MessageListData: Codable {
    let messages: [ArchivedMessage]
    let hasMore: Bool
    let nextStartMid: Int64
}

struct MessagePayload: Codable {
    let type: Int
    let content: String?
    let searchableContent: String?
    let pushContent: String?
    let pushData: String?
    let mentionedType: Int?
    let mentionedTargets: [String]?
    let remoteMediaUrl: String?
    let extra: String?
    let binaryContent: String?
    let subType: Int?
}

struct ArchivedMessage: Codable {
    let mid: Int64
    let senderId: String
    let convType: Int
    let convTarget: String
    let contentType: Int
    let payload: MessagePayload
    let searchableKey: String
    let messageDt: String
    // ...
}
```

### Web (JavaScript/TypeScript)

```typescript
interface FetchRequest {
  convType?: number;
  convTarget?: string;
  startMid?: number;
  before?: boolean;
  limit?: number;
}

interface MessageResponse {
  code: number;
  message: string;
  data?: {
    messages: ArchivedMessage[];
    hasMore: boolean;
    nextStartMid: number;
  };
}

interface MessagePayload {
  type: number;
  content?: string;
  searchableContent?: string;
  pushContent?: string;
  pushData?: string;
  mentionedType?: number;
  mentionedTargets?: string[];
  remoteMediaUrl?: string;
  extra?: string;       // JSON
  binaryContent?: string;  // 解析失败时的原始数据
  subType?: number;
}

interface ArchivedMessage {
  mid: number;
  senderId: string;
  convType: number;
  convTarget: string;
  contentType: number;
  payload: MessagePayload;  // 已解析的消息内容
  searchableKey: string;
  messageDt: string;    // ISO 8601 UTC
}

class ArchiveApiClient {
  private baseURL = 'https://archive.example.com';
  
  // 获取消息列表
  async fetchMessages(
    authCode: string, 
    params: FetchRequest
  ): Promise<MessageResponse> {
    const response = await fetch(`${this.baseURL}/api/messages/fetch`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'authCode': authCode
      },
      body: JSON.stringify(params)
    });
    
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    
    return response.json();
  }
  
  // 搜索消息
  async searchMessages(
    authCode: string,
    keyword: string,
    limit: number = 20
  ): Promise<MessageResponse> {
    const response = await fetch(`${this.baseURL}/api/messages/search`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'authCode': authCode
      },
      body: JSON.stringify({ keyword, limit })
    });
    
    return response.json();
  }
  
  // 转换 UTC 时间到本地时间显示
  formatMessageTime(utcTime: string): string {
    const date = new Date(utcTime + 'Z'); // 标记为 UTC
    return date.toLocaleString();
  }
  
  // 解析 extra JSON
  parseExtra(extra: string): any {
    try {
      return JSON.parse(extra);
    } catch {
      return {};
    }
  }
}

// 使用示例
const client = new ArchiveApiClient();

// 获取消息
async function loadMessages() {
  try {
    const response = await client.fetchMessages(authCode, {
      convType: 1,
      convTarget: 'group123',
      limit: 20
    });
    
    if (response.code === 0 && response.data) {
      const messages = response.data.messages;
      messages.forEach(msg => {
        const payload = msg.payload;
        console.log(`${msg.senderId}: ${payload.content || '[媒体消息]'}`);
        
        // 根据类型处理
        if (msg.contentType === 2 && payload.remoteMediaUrl) {
          console.log('图片:', payload.remoteMediaUrl);
        }
        
        console.log(client.formatMessageTime(msg.messageDt));
      });
    }
  } catch (error) {
    console.error('Failed to load messages:', error);
  }
}
```

---

## 4. 常见问题

### Q1: 如何使用消息内容 payload？

消息内容已经解析为 `payload` 对象，无需 Base64 解码，直接使用：

```java
// Android
MessagePayload payload = message.getPayload();
String text = payload.getContent();  // 文本消息内容
int type = payload.getType();        // 消息类型
String extra = payload.getExtra();   // 扩展字段（JSON）

// iOS  
let payload = message.payload
let text = payload.content
let type = payload.type
let extra = payload.extra

// Web
const payload = message.payload;
const text = payload.content;
const type = payload.type;
```

**不同消息类型的处理方式：**

```java
// 文本消息
if (message.getContentType() == 1) {
    String text = payload.getContent();
}

// 图片消息
if (message.getContentType() == 2) {
    String imageUrl = payload.getRemoteMediaUrl();
}

// 语音消息
if (message.getContentType() == 3) {
    String audioUrl = payload.getRemoteMediaUrl();
    // 解析 extra 获取时长
    JSONObject extra = new JSONObject(payload.getExtra());
    int duration = extra.getInt("duration");
}

// 位置消息
if (message.getContentType() == 6) {
    String title = payload.getContent();  // 位置名称
    JSONObject extra = new JSONObject(payload.getExtra());
    double longitude = extra.getDouble("longitude");
    double latitude = extra.getDouble("latitude");
}
```

### Q2: 时间格式如何处理？

`messageDt` 返回的是 UTC 时间（ISO 8601 格式），客户端需要转换为本地时间显示：

```java
// Android
Instant instant = Instant.parse(message.getMessageDt());
LocalDateTime localTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime();

// iOS
let dateFormatter = ISO8601DateFormatter()
let date = dateFormatter.date(from: message.messageDt)
// 再格式化为本地时间

// Web
const localTime = new Date(message.messageDt + 'Z').toLocaleString();
```

### Q3: 如何实现消息的无限滚动加载？

```
1. 首次加载：不传递 startMid，获取最新消息
2. 用户上滑加载更多：使用返回的 nextStartMid，before=true
3. 展示：将新数据追加到列表底部
4. 如果没有更多（hasMore=false），显示"没有更多消息"
```

### Q4: 搜索支持哪些字符？

- 支持中文、英文、数字
- 支持模糊匹配（子串匹配）
- 建议搜索词长度 >= 3 个字符
- 特殊字符会被原样搜索

### Q5: 认证失败（401）如何处理？

`authCode` 可能已过期，需要：
1. 通过野火IM SDK 重新获取 `authCode`
2. 使用新的 `authCode` 重新请求

---

## 5. 完整交互流程示例

### 场景：用户打开群组聊天，查看历史消息

```
1. 用户进入群组 "group123"
   
2. 客户端请求第一页消息
   POST /api/messages/fetch
   {
     "convType": 1,
     "convTarget": "group123",
     "limit": 20
   }
   
3. 服务端返回最新消息（mid 999-980）
   {
     "messages": [...],
     "hasMore": true,
     "nextStartMid": 980
   }
   
4. 用户上滑加载更多
   
5. 客户端请求更早的消息
   POST /api/messages/fetch
   {
     "convType": 1,
     "convTarget": "group123",
     "startMid": 980,
     "before": true,
     "limit": 20
   }
   
6. 服务端返回更早的消息（mid 979-960）
   {
     "messages": [...],
     "hasMore": true,
     "nextStartMid": 960
   }
   
7. 继续翻页直到 hasMore=false
```

### 场景：用户搜索消息

```
1. 用户在搜索框输入 "项目"

2. 客户端发起搜索请求
   POST /api/messages/search
   {
     "keyword": "项目",
     "limit": 20
   }

3. 服务端返回匹配的消息
   {
     "messages": [
       {"searchableKey": "项目进度正常", ...},
       {"searchableKey": "这个项目很好", ...}
     ],
     "hasMore": false
   }

4. 客户端展示搜索结果
```

---

## 6. 群组消息接口（超级群组）

> 注意：群组消息归档功能需要在配置中开启 `archive.task.sync-group-enabled=true`
> 
> **说明**：群组消息接口完全复用普通消息的 Request/Response 数据结构，查询的是独立的 `group_message_archive` 表。

### 6.1 获取群组消息列表

获取某个群组的历史消息列表。

#### 请求

```http
POST /api/group-messages/fetch
```

#### 请求参数

复用 `FetchRequest`，字段说明：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| convType | int | 否 | 1 | 固定为1（群组），可不传 |
| convTarget | string | **是** | - | 群组ID |
| convLine | int | 否 | 0 | 会话线路 |
| contentType | int | 否 | - | 消息内容类型筛选 |
| startMid | long | 否 | - | 起始消息ID（用于翻页） |
| before | boolean | 否 | true | true=查询更早的消息，false=查询更新的消息 |
| limit | int | 否 | 20 | 返回条数，最大100 |

#### 请求示例

**查询某个群组的最新消息（第一页）：**

```json
{
  "convTarget": "group123",
  "limit": 20
}
```

**翻页查询更早的消息：**

```json
{
  "convTarget": "group123",
  "startMid": 123456789,
  "before": true,
  "limit": 20
}
```

#### 响应参数

复用 `MessageFetchResponse`：

| 字段 | 类型 | 说明 |
|------|------|------|
| messages | array | 群组消息列表（数据结构同普通消息） |
| hasMore | boolean | 是否还有更多消息 |
| nextStartMid | long | 下一页起始消息ID |

#### 群组消息结构

> **说明**：群组消息响应复用普通消息的数据结构 `ArchivedMessageDTO`，其中：
> - `convType` 固定为 `1`（表示群组会话）
> - `convTarget` 为群组ID
> - `userId` 与 `senderId` 相同

| 字段 | 类型 | 说明 |
|------|------|------|
| mid | long | 消息唯一ID |
| senderId | string | 发送者用户ID |
| convType | int | 会话类型，固定为 `1`（群组） |
| convTarget | string | 会话目标，即群组ID |
| convLine | int | 会话线路 |
| contentType | int | 消息内容类型 |
| payload | object | 消息内容（解析后的 MessagePayload） |
| userId | string | 当前用户ID（与发送者相同） |
| searchableKey | string | 可搜索文本 |
| messageDt | string | 消息发送时间（ISO 8601格式，UTC时间） |

### 6.2 搜索群组消息

根据关键字搜索群组消息内容。

#### 请求

```http
POST /api/group-messages/search
```

#### 请求参数

复用 `SearchRequest`，字段说明：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| keyword | string | **是** | - | 搜索关键字 |
| convTarget | string | **是** | - | 群组ID（必须提供；仅能搜索当前用户所在群组） |
| convLine | int | 否 | - | 会话线路筛选 |
| contentType | int | 否 | - | 内容类型筛选 |
| startMid | long | 否 | - | 起始消息ID |
| before | boolean | 否 | true | 翻页方向 |
| limit | int | 否 | 20 | 返回条数，最大100 |

#### 请求示例

**在指定群组中搜索：**

```json
{
  "convTarget": "group123",
  "keyword": "项目进度",
  "limit": 20
}
```

> 出于权限控制，不再支持不指定群组 ID 的“搜索所有群组”。调用方必须传 `convTarget`，
> 服务端会通过 IM 接口校验当前用户是否是该群成员，非成员返回 403。

#### 响应参数

复用 `MessageFetchResponse`，与 `/api/group-messages/fetch` 相同。
