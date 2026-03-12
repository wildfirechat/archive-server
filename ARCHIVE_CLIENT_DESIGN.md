# 消息归档服务客户端集成设计文档

## 1. 需求概述

### 1.1 当前消息加载流程
```
用户加载历史消息
    ↓
从本地数据库获取消息 (getMessagesV2)
    ↓
本地有消息? → 是 → 显示本地消息
    ↓ 否
从远程IM服务获取消息 (getRemoteMessages)
    ↓
远程有消息? → 是 → 保存到本地并显示
    ↓ 否
【当前】没有更多消息了
```

### 1.2 新消息加载流程（添加归档服务）
```
用户加载历史消息
    ↓
从本地数据库获取消息 (getMessagesV2)
    ↓
本地有消息? → 是 → 显示本地消息
    ↓ 否
从远程IM服务获取消息 (getRemoteMessages)
    ↓
远程有消息? → 是 → 保存到本地并显示
    ↓ 否
从归档服务获取消息 (getArchivedMessages) ← 【新增】
    ↓
归档服务有消息? → 是 → 保存到本地并显示（可选）
    ↓ 否
没有更多消息了
```

## 2. 架构设计

参考 Poll 功能的实现方式，采用**协议+默认实现+可注入**的架构模式。

### 2.1 类结构设计

```
┌─────────────────────────────────────────────────────────────────┐
│                      UI 层 (WFChatUIKit)                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │           WFCUMessageListViewController                  │   │
│  │  - loadLocalMessages()                                   │   │
│  │  - loadRemoteHistoryMessages()                           │   │
│  │  - loadArchivedMessages()  ← 【新增方法】                 │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                   服务协议层 (WFChatUIKit)                        │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │           @protocol WFCUArchiveService                   │   │
│  │  - getArchivedMessages:convType:convTarget:...          │   │
│  │  - searchArchivedMessages:keyword:...                   │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                   默认实现层 (WFChatUIKit)                        │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │           WFCUArchiveServiceImpl                         │   │
│  │  - 实现 WFCUArchiveService 协议                          │   │
│  │  - 调用归档服务 HTTP API                                 │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                   应用自定义层 (WildFireChat)                     │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │           ArchiveService (可选自定义)                    │   │
│  │  - 可继承 WFCUArchiveServiceImpl 进行扩展                 │   │
│  │  - 或完全自定义实现 WFCUArchiveService 协议               │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 与 Poll 功能对比

| 组件 | Poll 功能 | 归档服务功能 |
|------|----------|-------------|
| 协议 | `WFCUPollService` | `WFCUArchiveService` |
| 默认实现 | `WFCUPollServiceImpl` | `WFCUArchiveServiceImpl` |
| 配置入口 | `WFCUConfigManager.pollServiceProvider` | `WFCUConfigManager.archiveServiceProvider` |
| 数据模型 | `WFCUPoll` | `WFCUArchivedMessage` |

## 3. 详细设计

### 3.1 数据模型

#### WFCUMessagePayload.h（消息内容）

```objc
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * 消息内容 Payload
 * 对应服务端 API 返回的 payload 结构（已解析的消息内容）
 */
@interface WFCUMessagePayload : NSObject

/// 消息类型
@property (nonatomic, assign) int type;

/// 消息文本内容（如文本消息的实际内容、位置标题、链接标题等）
@property (nonatomic, strong, nullable) NSString *content;

/// 可搜索内容
@property (nonatomic, strong, nullable) NSString *searchableContent;

/// 推送内容
@property (nonatomic, strong, nullable) NSString *pushContent;

/// 推送数据
@property (nonatomic, strong, nullable) NSString *pushData;

/// @类型：0-不提醒，1-所有人，2-部分人
@property (nonatomic, assign) int mentionedType;

/// @的目标用户ID列表
@property (nonatomic, strong, nullable) NSArray<NSString *> *mentionedTargets;

/// 媒体文件远程URL（图片、语音、文件、视频等）
@property (nonatomic, strong, nullable) NSString *remoteMediaUrl;

/// 扩展字段（JSON格式，包含类型特定的额外信息）
@property (nonatomic, strong, nullable) NSString *extra;

/// 原始Base64数据（解析失败时保留）
@property (nonatomic, strong, nullable) NSString *binaryContent;

/// 子类型（如Typing消息的类型）
@property (nonatomic, assign) int subType;

#pragma mark - 便捷方法

/**
 * 从字典创建 payload 对象
 */
+ (nullable instancetype)fromDictionary:(NSDictionary *)dict;

/**
 * 将 payload 转换为 WFCCMessagePayload（SDK 使用的格式）
 * @param contentType 消息内容类型
 */
- (WFCCMessagePayload *)toSDKPayload:(int)contentType;

@end

NS_ASSUME_NONNULL_END
```

#### WFCUArchivedMessage.h（归档消息）

```objc
#import <Foundation/Foundation.h>
#import "WFCUMessagePayload.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * 归档消息模型
 * 对应归档服务 API 返回的消息结构
 */
@interface WFCUArchivedMessage : NSObject

/// 消息唯一ID
@property (nonatomic, assign) int64_t mid;

/// 发送者用户ID
@property (nonatomic, strong) NSString *senderId;

/// 会话类型：0单聊, 1群组, 2聊天室, 3频道
@property (nonatomic, assign) int convType;

/// 会话目标（用户ID或群组ID）
@property (nonatomic, strong) NSString *convTarget;

/// 会话线路
@property (nonatomic, assign) int convLine;

/// 消息内容类型
@property (nonatomic, assign) int contentType;

/// 消息内容（已解析的 payload，替代旧的 contentData Base64）
@property (nonatomic, strong, nullable) WFCUMessagePayload *payload;

/// 可搜索文本
@property (nonatomic, strong, nullable) NSString *searchableKey;

/// 当前用户ID（用户视角）
@property (nonatomic, strong) NSString *userId;

/// 消息发送时间（ISO 8601格式，UTC时间）
@property (nonatomic, strong) NSString *messageDt;

/// 用户哈希值（用于分区）
@property (nonatomic, assign) int userHash;

#pragma mark - 便捷方法

/**
 * 从字典创建归档消息对象
 */
+ (nullable instancetype)fromDictionary:(NSDictionary *)dict;

/**
 * 获取本地时间格式的消息时间
 */
- (NSDate *)localMessageDate;

@end

/**
 * 归档消息查询结果
 */
@interface WFCUArchivedMessageResult : NSObject

/// 消息列表
@property (nonatomic, strong) NSArray<WFCUArchivedMessage *> *messages;

/// 是否还有更多消息
@property (nonatomic, assign) BOOL hasMore;

/// 下一页起始消息ID（用于翻页）
@property (nonatomic, assign) int64_t nextStartMid;

+ (nullable instancetype)fromDictionary:(NSDictionary *)dict;

@end

NS_ASSUME_NONNULL_END
```

### 3.2 服务协议 (WFCUArchiveService.h)

```objc
#import <Foundation/Foundation.h>
#import "WFCUArchivedMessage.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * 消息归档服务协议
 * 用于从归档服务获取历史消息
 */
@protocol WFCUArchiveService <NSObject>

#pragma mark - 获取归档消息

/**
 * 获取某个会话的历史消息列表
 *
 * @param conversationType 会话类型：0单聊, 1群组, 2聊天室, 3频道
 * @param convTarget 会话目标（用户ID或群组ID）
 * @param convLine 会话线路，默认0
 * @param startMid 起始消息ID，第一次传0或-1
 * @param before YES=查询更早的消息（向前翻页），NO=查询更新的消息
 * @param limit 返回条数，最大100
 * @param successBlock 成功回调，返回消息列表和分页信息
 * @param errorBlock 失败回调
 */
- (void)getArchivedMessages:(int)conversationType
                 convTarget:(NSString *)convTarget
                   convLine:(int)convLine
                   startMid:(int64_t)startMid
                     before:(BOOL)before
                      limit:(int)limit
                    success:(void(^)(WFCUArchivedMessageResult *result))successBlock
                      error:(void(^)(int errorCode, NSString *message))errorBlock;

/**
 * 便捷方法：获取某个会话的历史消息（使用默认线路0）
 */
- (void)getArchivedMessages:(int)conversationType
                 convTarget:(NSString *)convTarget
                   startMid:(int64_t)startMid
                     before:(BOOL)before
                      limit:(int)limit
                    success:(void(^)(WFCUArchivedMessageResult *result))successBlock
                      error:(void(^)(int errorCode, NSString *message))errorBlock;

#pragma mark - 搜索归档消息

/**
 * 根据关键字搜索归档消息
 *
 * @param keyword 搜索关键字（长度建议 >= 3）
 * @param conversationType 会话类型筛选，可选
 * @param convTarget 会话目标筛选，可选
 * @param convLine 会话线路筛选，可选
 * @param startMid 起始消息ID，可选
 * @param before 翻页方向，默认YES
 * @param limit 返回条数，最大100
 * @param successBlock 成功回调
 * @param errorBlock 失败回调
 */
- (void)searchArchivedMessages:(NSString *)keyword
              conversationType:(nullable NSNumber *)conversationType
                    convTarget:(nullable NSString *)convTarget
                      convLine:(nullable NSNumber *)convLine
                      startMid:(int64_t)startMid
                        before:(BOOL)before
                         limit:(int)limit
                       success:(void(^)(WFCUArchivedMessageResult *result))successBlock
                         error:(void(^)(int errorCode, NSString *message))errorBlock;

/**
 * 便捷方法：搜索所有归档消息
 */
- (void)searchArchivedMessages:(NSString *)keyword
                         limit:(int)limit
                       success:(void(^)(WFCUArchivedMessageResult *result))successBlock
                         error:(void(^)(int errorCode, NSString *message))errorBlock;

@end

NS_ASSUME_NONNULL_END
```

### 3.3 默认实现 (WFCUArchiveServiceImpl.h/m)

```objc
// WFCUArchiveServiceImpl.h
#import <Foundation/Foundation.h>
#import "WFCUArchiveService.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * 消息归档服务默认实现类
 * 使用示例：
 *   WFCUArchiveServiceImpl *service = [[WFCUArchiveServiceImpl alloc] init];
 *   service.baseUrl = @"https://archive.example.com";
 *   [WFCUConfigManager globalManager].archiveServiceProvider = service;
 */
@interface WFCUArchiveServiceImpl : NSObject <WFCUArchiveService>

/// 归档服务基础URL，如 https://archive.example.com
@property (nonatomic, strong) NSString *baseUrl;

/// 获取当前用户的 authCode（用于认证）
/// 如果不设置，将使用默认实现从 WFCCIMService 获取
@property (nonatomic, copy, nullable) NSString *(^authCodeProvider)(void);

/// 超时时间，默认30秒
@property (nonatomic, assign) NSTimeInterval timeoutInterval;

@end

NS_ASSUME_NONNULL_END
```

```objc
// WFCUArchiveServiceImpl.m（关键方法）

#import "WFCUArchiveServiceImpl.h"
#import <WFChatClient/WFCCIMService.h>
#import <WFChatClient/WFCCNetworkService.h>
#import "AFNetworking.h"

@implementation WFCUArchiveServiceImpl

- (instancetype)init {
    self = [super init];
    if (self) {
        _timeoutInterval = 30.0;
    }
    return self;
}

#pragma mark - WFCUArchiveService

- (void)getArchivedMessages:(int)conversationType
                 convTarget:(NSString *)convTarget
                   convLine:(int)convLine
                   startMid:(int64_t)startMid
                     before:(BOOL)before
                      limit:(int)limit
                    success:(void(^)(WFCUArchivedMessageResult *result))successBlock
                      error:(void(^)(int errorCode, NSString *message))errorBlock {
    
    // 构建请求参数
    NSMutableDictionary *params = [@{
        @"convType": @(conversationType),
        @"convTarget": convTarget ?: @"",
        @"convLine": @(convLine),
        @"before": @(before),
        @"limit": @(MIN(limit, 100))
    } mutableCopy];
    
    if (startMid > 0) {
        params[@"startMid"] = @(startMid);
    }
    
    [self postWithAuth:@"/api/messages/fetch" parameters:params success:^(NSDictionary *dict) {
        if ([dict[@"code"] intValue] == 0) {
            WFCUArchivedMessageResult *result = [WFCUArchivedMessageResult fromDictionary:dict[@"data"]];
            if (successBlock) successBlock(result);
        } else {
            if (errorBlock) errorBlock([dict[@"code"] intValue], dict[@"message"] ?: @"Unknown error");
        }
    } error:^(int errorCode, NSString *message) {
        if (errorBlock) errorBlock(errorCode, message);
    }];
}

- (void)searchArchivedMessages:(NSString *)keyword
              conversationType:(nullable NSNumber *)conversationType
                    convTarget:(nullable NSString *)convTarget
                      convLine:(nullable NSNumber *)convLine
                      startMid:(int64_t)startMid
                        before:(BOOL)before
                         limit:(int)limit
                       success:(void(^)(WFCUArchivedMessageResult *result))successBlock
                         error:(void(^)(int errorCode, NSString *message))errorBlock {
    
    NSMutableDictionary *params = [@{
        @"keyword": keyword ?: @"",
        @"before": @(before),
        @"limit": @(MIN(limit, 100))
    } mutableCopy];
    
    if (conversationType) params[@"convType"] = conversationType;
    if (convTarget) params[@"convTarget"] = convTarget;
    if (convLine) params[@"convLine"] = convLine;
    if (startMid > 0) params[@"startMid"] = @(startMid);
    
    [self postWithAuth:@"/api/messages/search" parameters:params success:^(NSDictionary *dict) {
        if ([dict[@"code"] intValue] == 0) {
            WFCUArchivedMessageResult *result = [WFCUArchivedMessageResult fromDictionary:dict[@"data"]];
            if (successBlock) successBlock(result);
        } else {
            if (errorBlock) errorBlock([dict[@"code"] intValue], dict[@"message"] ?: @"Unknown error");
        }
    } error:^(int errorCode, NSString *message) {
        if (errorBlock) errorBlock(errorCode, message);
    }];
}

#pragma mark - HTTP Helper

- (void)postWithAuth:(NSString *)path
          parameters:(NSDictionary *)parameters
             success:(void(^)(NSDictionary *dict))successBlock
               error:(void(^)(int errorCode, NSString *message))errorBlock {
    
    // 获取 authCode
    if (self.authCodeProvider) {
        NSString *authCode = self.authCodeProvider();
        [self post:path parameters:parameters authCode:authCode success:successBlock error:errorBlock];
    } else {
        // 默认从 IMService 获取 authCode
        [[WFCCIMService sharedWFCIMService] getAuthCode:@"admin" type:2 host:nil success:^(NSString *authCode) {
            [self post:path parameters:parameters authCode:authCode success:successBlock error:errorBlock];
        } error:^(int error_code) {
            if (errorBlock) errorBlock(error_code, @"Failed to get authCode");
        }];
    }
}

- (void)post:(NSString *)path
  parameters:(NSDictionary *)parameters
    authCode:(NSString *)authCode
     success:(void(^)(NSDictionary *dict))successBlock
       error:(void(^)(int errorCode, NSString *message))errorBlock {
    
    if (!self.baseUrl.length) {
        if (errorBlock) errorBlock(-1, @"Base URL not set");
        return;
    }
    
    AFHTTPSessionManager *manager = [AFHTTPSessionManager manager];
    manager.requestSerializer = [AFJSONRequestSerializer serializer];
    manager.responseSerializer = [AFJSONResponseSerializer serializer];
    manager.requestSerializer.timeoutInterval = self.timeoutInterval;
    
    if (authCode.length > 0) {
        [manager.requestSerializer setValue:authCode forHTTPHeaderField:@"authCode"];
    }
    
    NSString *url = [self.baseUrl stringByAppendingString:path];
    
    [manager POST:url parameters:parameters progress:nil success:^(NSURLSessionDataTask *task, id responseObject) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if ([responseObject isKindOfClass:[NSDictionary class]]) {
                successBlock((NSDictionary *)responseObject);
            } else {
                if (errorBlock) errorBlock(-1, @"Invalid response format");
            }
        });
    } failure:^(NSURLSessionDataTask *task, NSError *error) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (errorBlock) errorBlock((int)error.code, error.localizedDescription);
        });
    }];
}

@end
```

### 3.4 WFCUConfigManager 扩展

```objc
// 在 WFCUConfigManager.h 中添加：

#import "WFCUArchiveService.h"

@interface WFCUConfigManager : NSObject

// ... 现有属性 ...

@property(nonatomic, weak) id<WFCUArchiveService> archiveServiceProvider;

@end
```

## 4. 在 WFCUMessageListViewController 中集成

### 4.1 修改消息加载逻辑

```objc
// WFCUMessageListViewController.m

- (void)loadRemoteHistoryMessages:(void(^)(BOOL hasMore))completion {
    __weak typeof(self) weakSelf = self;
    
    // 1. 先从远程IM服务获取
    [[WFCCIMService sharedWFCIMService] getRemoteMessages:weakSelf.conversation 
                                                     before:self.lastUid 
                                                      count:10 
                                               contentTypes:nil 
                                                    success:^(NSArray<WFCCMessage *> *messages) {
        // 处理远程消息...
        if (messages.count > 0) {
            // 有消息，正常处理
            // ...
            if (completion) completion(YES);
        } else {
            // 远程IM服务没有更多消息，尝试从归档服务获取
            [weakSelf loadArchivedHistoryMessages:completion];
        }
    } error:^(int error_code) {
        // 远程获取失败，尝试从归档服务获取
        [weakSelf loadArchivedHistoryMessages:completion];
    }];
}

/**
 * 【新增】从归档服务加载历史消息
 */
- (void)loadArchivedHistoryMessages:(void(^)(BOOL hasMore))completion {
    // 检查是否配置了归档服务
    id<WFCUArchiveService> archiveService = [WFCUConfigManager globalManager].archiveServiceProvider;
    if (!archiveService) {
        // 没有配置归档服务，直接标记没有更多消息
        weakSelf.hasMoreOld = NO;
        if (completion) completion(NO);
        return;
    }
    
    __weak typeof(self) weakSelf = self;
    
    // 转换 lastUid 为 startMid
    int64_t startMid = self.lastUid > 0 ? self.lastUid : 0;
    
    [archiveService getArchivedMessages:(int)self.conversation.type
                             convTarget:self.conversation.target
                               convLine:(int)self.conversation.line
                               startMid:startMid
                                 before:YES
                                  limit:10
                                success:^(WFCUArchivedMessageResult *result) {
        
        if (result.messages.count == 0) {
            // 归档服务也没有更多消息
            weakSelf.hasMoreOld = NO;
            if (completion) completion(NO);
            return;
        }
        
        // 将归档消息转换为 WFCCMessage 并处理
        // 注意：归档消息的内容已经解析为 payload 对象，无需 Base64 解码
        NSMutableArray *convertedMessages = [NSMutableArray array];
        for (WFCUArchivedMessage *archivedMsg in result.messages) {
            // 创建 WFCCMessage 对象
            WFCCMessage *message = [weakSelf convertArchivedMessageToWFCCMessage:archivedMsg];
            if (message) {
                [convertedMessages addObject:message];
            }
        }
        
        // 更新 lastUid（使用 nextStartMid）
        if (result.nextStartMid > 0) {
            weakSelf.lastUid = result.nextStartMid;
        }
        
        // 更新 hasMore 标志
        weakSelf.hasMoreOld = result.hasMore;
        
        // 在主线程刷新 UI
        dispatch_async(dispatch_get_main_queue(), ^{
            // 反转消息顺序（因为归档服务返回的是按时间降序）
            NSArray *reversedMsgs = [[convertedMessages reverseObjectEnumerator] allObjects];
            
            // 添加到消息列表（不保存到本地数据库，因为是归档消息）
            [weakSelf appendArchivedMessages:reversedMsgs];
            
            if (completion) completion(convertedMessages.count > 0);
        });
        
    } error:^(int errorCode, NSString *message) {
        // 归档服务获取失败
        NSLog(@"Failed to load archived messages: %d, %@", errorCode, message);
        weakSelf.hasMoreOld = NO;
        if (completion) completion(NO);
    }];
}

/**
 * 【新增】将归档消息转换为 WFCCMessage
 */
- (nullable WFCCMessage *)convertArchivedMessageToWFCCMessage:(WFCUArchivedMessage *)archivedMsg {
    if (!archivedMsg || !archivedMsg.payload) return nil;
    
    // 创建 WFCCMessage 对象
    WFCCMessage *message = [[WFCCMessage alloc] init];
    // 从远程同步回来的消息，没有消息ID，使用递减的负数确保唯一
    static long long messageIdCounter = 0;
    messageIdCounter--;
    message.messageId = messageIdCounter;
    message.messageUid = archivedMsg.mid;
    message.fromUser = archivedMsg.senderId;
    
    // 判断消息方向
    NSString *currentUserId = [WFCCNetworkService sharedInstance].userId;
    if (currentUserId && [currentUserId isEqualToString:archivedMsg.senderId]) {
        message.direction = MessageDirection_Send;
        message.status = Message_Status_Sent;
    } else {
        message.direction = MessageDirection_Receive;
        message.status = Message_Status_Readed;
    }
    
    // 设置会话信息
    message.conversation = [[WFCCConversation alloc] init];
    message.conversation.type = archivedMsg.convType;
    message.conversation.target = archivedMsg.convTarget;
    message.conversation.line = archivedMsg.convLine;
    
    // 转换时间为毫秒时间戳
    message.serverTime = [archivedMsg localMessageDate].timeIntervalSince1970 * 1000;
    
    // 使用 payload 创建 SDK 的 WFCCMessagePayload
    WFCCMessagePayload *sdkPayload = [archivedMsg.payload toSDKPayload:archivedMsg.contentType];
    
    // 使用 IMService 解析消息内容
    WFCCMessageContent *content = [[WFCCIMService sharedWFCIMService] messageContentFromPayload:sdkPayload];
    if (!content) {
        content = [[WFCCUnknownMessageContent alloc] init];
    }
    message.content = content;
    
    return message;
}

/**
 * 【新增】添加归档消息到列表（特殊处理，不保存到本地）
 */
- (void)appendArchivedMessages:(NSArray<WFCCMessage *> *)messages {
    // 与 appendMessages 类似，但标记为归档消息
    // 归档消息通常不需要保存到本地数据库，只显示在列表中
    // 可以添加特殊标记来区分本地/远程/归档消息
    
    // ... 实现与现有 appendMessages 类似 ...
}
```

## 5. 应用层使用示例

### 5.1 在 AppDelegate 中配置

```objc
// AppDelegate.m

#import "AppDelegate.h"
#import <WFChatUIKit/WFCUConfigManager.h>
#import <WFChatUIKit/WFCUArchiveServiceImpl.h>

@implementation AppDelegate

- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    
    // ... 其他初始化代码 ...
    
    // 配置消息归档服务
    [self setupArchiveService];
    
    return YES;
}

- (void)setupArchiveService {
    // 创建归档服务实例
    WFCUArchiveServiceImpl *archiveService = [[WFCUArchiveServiceImpl alloc] init];
    
    // 设置归档服务基础URL
    archiveService.baseUrl = @"https://archive.yourdomain.com";
    
    // 可选：自定义 authCode 获取方式
    // archiveService.authCodeProvider = ^NSString * {
    //     return [self getCustomAuthCode];
    // };
    
    // 设置超时时间
    archiveService.timeoutInterval = 30.0;
    
    // 注入到配置管理器
    [WFCUConfigManager globalManager].archiveServiceProvider = archiveService;
}

@end
```

### 5.2 自定义实现（可选）

如果应用需要自定义归档服务行为，可以实现自己的服务类：

```objc
// CustomArchiveService.h
#import <Foundation/Foundation.h>
#import <WFChatUIKit/WFCUArchiveService.h>

@interface CustomArchiveService : NSObject <WFCUArchiveService>

@property (nonatomic, strong) NSString *baseUrl;

@end
```

```objc
// CustomArchiveService.m
#import "CustomArchiveService.h"

@implementation CustomArchiveService

- (void)getArchivedMessages:(int)conversationType
                 convTarget:(NSString *)convTarget
                   convLine:(int)convLine
                   startMid:(int64_t)startMid
                     before:(BOOL)before
                      limit:(int)limit
                    success:(void(^)(WFCUArchivedMessageResult *result))successBlock
                      error:(void(^)(int errorCode, NSString *message))errorBlock {
    
    // 自定义实现，例如：
    // 1. 添加额外的请求头
    // 2. 对返回数据进行特殊处理
    // 3. 添加缓存逻辑
    // 4. 记录统计信息
    
    // ... 自定义 HTTP 请求 ...
}

// ... 实现其他方法 ...

@end
```

## 6. 注意事项

### 6.1 消息内容解析

归档服务返回的消息内容已经解析为 `payload` 对象，客户端无需 Base64 解码，直接使用即可：

```objc
// 使用示例
- (void)handleArchivedMessage:(WFCUArchivedMessage *)archivedMsg {
    WFCUMessagePayload *payload = archivedMsg.payload;
    
    // 获取消息文本内容
    NSString *text = payload.content;
    
    // 获取媒体URL（图片、语音、文件等）
    NSString *mediaUrl = payload.remoteMediaUrl;
    
    // 解析 extra JSON 获取额外信息
    NSData *extraData = [payload.extra dataUsingEncoding:NSUTF8StringEncoding];
    NSDictionary *extra = [NSJSONSerialization JSONObjectWithData:extraData options:0 error:nil];
    
    // 例如：语音消息的时长
    if (archivedMsg.contentType == 3) {  // 语音消息
        int duration = [extra[@"duration"] intValue];
    }
}

// 转换为 SDK 消息内容
- (WFCCMessageContent *)parseArchivedContent:(WFCUArchivedMessage *)archivedMsg {
    if (!archivedMsg.payload) return nil;
    
    // 直接使用 payload 的 toSDKPayload 方法转换
    WFCCMessagePayload *sdkPayload = [archivedMsg.payload toSDKPayload:archivedMsg.contentType];
    
    return [[WFCCIMService sharedWFCIMService] messageContentFromPayload:sdkPayload];
}
```

### 6.2 分页处理

归档服务使用 `startMid` 和 `before` 参数进行分页：

```objc
// 首次加载（获取最新消息）
startMid = 0
before = YES

// 翻页加载更早的消息（使用上次返回的 nextStartMid）
startMid = result.nextStartMid
before = YES

// 加载更新的消息（轮询新消息）
startMid = knownMaxMid
before = NO
```

### 6.3 与现有流程的兼容性

- 归档消息只读显示，不保存到本地数据库
- 用户发送的新消息正常走原有流程
- 如果用户清空了本地数据库，再次加载时会重新从归档服务获取

### 6.4 错误处理

```objc
// 建议的错误处理策略
- (void)handleArchiveError:(int)errorCode message:(NSString *)message {
    switch (errorCode) {
        case 401: // 认证失败
            // 尝试刷新 authCode 后重试
            break;
        case 500: // 服务器错误
            // 显示错误提示，允许用户重试
            break;
        case -1:  // 网络错误
            // 检查网络连接
            break;
        default:
            break;
    }
}
```

## 7. 测试建议

1. **正常流程测试**：本地无消息 → 远程无消息 → 从归档服务获取
2. **分页测试**：验证翻页加载更多归档消息
3. **网络异常测试**：归档服务不可用时降级处理
4. **认证测试**：authCode 过期后的自动刷新
5. **数据解析测试**：各种类型的消息内容正确解析显示（验证 payload 的各个字段）
6. **兼容性测试**：验证旧版使用 contentData 的代码已更新为使用 payload

## 8. 总结

本设计参考了 Poll 功能的实现方式，采用**协议+默认实现+可注入**的架构，具有以下优点：

1. **低侵入性**：不修改现有消息加载流程，只是添加降级方案
2. **可扩展性**：应用层可以自定义实现，满足特殊需求
3. **向后兼容**：如果不配置归档服务，现有功能不受影响
4. **与现有架构一致**：与 Poll 功能保持相同的代码风格和使用方式
