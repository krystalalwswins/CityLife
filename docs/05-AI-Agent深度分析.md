# AI Agent 深度分析

这个项目的 AI 部分不是简单调用一次 LLM，而是一个真实可追踪的“手写 ReAct/Think-Execute”循环。

## 1. 核心结论：真实的 Agent Loop 在哪里

真实 Loop 在：

```text
src/main/java/com/hmdp/ai/agent/loop/ManualToolAgentLoopExecutor.java
```

它是项目 Agent 的核心。不要被 Spring AI 的自动 tool execution 迷惑，这里显式关闭了自动执行：

```java
OpenAiChatOptions.builder()
    .internalToolExecutionEnabled(false)
    .parallelToolCalls(false)
    .toolCallbacks(toolRegistry.getToolCallbacks())
    .build();
```

含义：

- 让模型只输出“要调用哪些工具”的 ToolCall 列表。
- Java 代码自己执行工具。
- 工具结果作为 `ToolResponseMessage` 塞回下一轮上下文。
- 代码控制步数、空响应、未注册工具、工具异常和终止。

## 2. Agent Loop 执行流程

```text
AgentLoopExecutor
  → ManualToolAgentLoopExecutor.execute(request, toolLogs, observer)

初始化：
  execution.maxSteps = max(ai.agent.multi-agent.max-steps, 1)
  messages = [SystemMessage]
  messages += historyMessages
  messages += UserMessage(buildUserPrompt(request))

for step = 1..maxSteps:
  1. trace THINKING
  2. response = chatModel.call(new Prompt(messages, buildLoopOptions()))
  3. assistantMessage = response.result.output
  4. messages.add(assistantMessage)

  5. 如果没有 toolCalls：
       - 有文本：FINISHED，返回 finalAnswer
       - 无文本：blankResponseCount++
         - 若还能继续：追加 retryPrompt，continue
         - 否则：ERROR，终止

  6. 如果有 toolCalls：
       trace EXECUTING，记录 toolNames

       6.1 尝试提取 terminate：
            如果只有 terminate 一个调用且 answer 非空：
                FINISHED + terminatedByTool=true

       6.2 遍历其他工具：
             - toolRegistry.getToolCallback(name)
             - 不存在：ERROR，终止
             - 执行 toolCallback.call(arguments, ToolContext)
             - 结果放进 ToolResponseMessage
             - 写 toolLogs，通知 observer

       6.3 如果有 toolResponses：
             messages.add(new ToolResponseMessage(...))
             simpleRecommendation 场景可能追加 finishPrompt

循环结束仍未完成：
  ERROR：超过最大步数
```

## 3. 关键类职责

| 类 | 职责 |
| --- | --- |
| `AgentLoopRequest` | 用户问题、坐标、店铺 ID、topK、历史消息 |
| `AgentLoopExecution` | 状态、总步数、最终答案、trace |
| `AgentLoopTrace` | 单步 trace，含 step/status/detail/toolNames |
| `ToolExecutionLog` | 工具名 + payload，落库与 SSE 使用 |
| `AgentLoopStatus` | IDLE/THINKING/EXECUTING/FINISHED/ERROR |
| `AgentLoopExecutor` | 对外统一入口 |
| `ManualToolAgentLoopExecutor` | 真正的循环和边界控制 |
| `AgentLoopToolRegistry` | 把 @Tool 方法转换成 ToolCallback 并建立名称索引 |
| `AgentLoopObserver` | 回调接口，用于 SSE 与日志 |

## 4. 提示词设计

`AgentLoopPromptProvider`：

- `systemPrompt`：定义 Think-Execute-Respond 模式。
- `buildUserPrompt`：注入用户问题、坐标、店铺 ID、topK。
- `blankResponseRetryPrompt`：处理模型空输出。
- `simpleRecommendationFinishPrompt`：轻量推荐场景诱导尽早结束。
- `finalAnswerSystemPrompt` / `buildFinalAnswerPrompt`：二次整理最终答案。

System Prompt 中几个关键约束：

- 不必要不调用工具。
- 综合攻略要搜索真实店铺。
- 每轮必须增量决策，禁止重复空转。
- 信息足够必须调用 `terminate(answer, reason)`。

## 5. Tool Calling 实现

### 5.1 注册

```java
ToolCallbacks.from(dianPingAgentTools, controlTools, mcpLocalLifeTools)
```

`AgentLoopToolRegistry` 把它们转成 `ToolCallback` 列表，并用工具名做 Map 索引。

### 5.2 本地工具

`DianPingAgentTools`：

- `getShopDetail(shopId)`
- `searchNearbyShops(typeId, x, y, page)`
- `searchShopsByKeyword(keyword)`
- `searchKnowledgeBase(query)`
- `getShopReviewTexts(shopId)`
- `getShopReviewSummary(shopId)`
- `vectorSearchShopProfiles(query, topK)`
- `vectorSearchBlogs(query, topK)`
- `vectorSearchBlogsByShop(shopId, query, topK)`

`AgentLoopControlTools`：

- `terminate(answer, reason)`

`McpLocalLifeTools`：

- `getRouteAdvice(...)`
- `getWeatherDiningAdvice(city, diningScene)`

### 5.3 手动执行细节

`ToolCallback.call(arguments, ToolContext)`，其中：

```java
ToolContext.TOOL_CALL_HISTORY -> List.copyOf(messages)
```

也就是说，工具执行时可以拿到完整对话历史。

每个工具结果：

```java
new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), responseData)
```

下一轮模型会看到工具返回结果，并决定继续调用还是 `terminate`。

### 5.4 terminate 特殊处理

`terminate` 不真正执行工具回调，而是在 Java 侧解析参数：

```java
extractTerminateDecision(toolCalls)
```

解析出 `answer` 和 `reason` 后直接结束，避免再调一次模型。

## 6. RAG 实现

### 6.1 三个向量集合

| Bean | collection | 数据来源 | 检索目的 |
| --- | --- | --- | --- |
| `knowledgeVectorStore` | `knowledge_vector` | `classpath:knowledge/*.txt` | 平台规则/高确定性知识 |
| `shopProfileVectorStore` | `shop_profile_vector` | `tb_shop` | 店铺推荐 |
| `blogReviewVectorStore` | `blog_review_vector` | `tb_blog` | 评论/笔记分析 |

配置在 `application.yaml`：

```yaml
spring.ai.vectorstore.milvus:
  initialize-schema: true
  database-name: default
  embedding-dimension: 1024
  client:
    host: localhost
    port: 19530
```

Embedding 模型：

```yaml
spring.ai.openai.embedding.options.model: text-embedding-v4
```

### 6.2 索引构建

`AiVectorIndexServiceImpl`：

- 知识库：`TextReader` 读 txt → `TokenTextSplitter` 切块 → metadata 写入 `docType=knowledge`。
- 店铺画像：把结构化字段拼成文本，metadata 保存可解释字段。
- 探店笔记：把“标题 + 店铺名 + 正文”拼成文本，降低短评论语义损失。

重建方式：

- `rebuildAll()`：先按 metadata filter 删除旧文档，再全量重建三类。
- `rebuildShop(shopId)`：删除某店铺画像与评论向量，再重建该店铺。

启动初始化：

```text
KnowledgeBaseInitializer
  → AiVectorIndexService.ensureInitialized()
       ├─ 知识库为空 → rebuildKnowledge
       ├─ 店铺画像为空 → rebuildAllShopProfiles
       └─ 评论为空 → rebuildAllBlogReviews
```

### 6.3 检索策略

`AiRagRetriever.searchKnowledge`：

```text
向量召回
  knowledgeVectorStore.similaritySearch(
    topK=5,
    similarityThreshold=0.35
  )

关键词 fallback
  keywordFallbackKnowledgeSearch
    → 扫 classpath knowledge/*.txt
    → 按空行切段
    → 每段计算 keywordScore

如果 fallback 有结果：
  → 过滤出“有关键词命中的向量结果”
  → merge + 去重 + 排序 + limit topK
```

店铺画像和探店笔记则走向量检索：

```text
shopProfileVectorStore.similaritySearch(...)
blogReviewVectorStore.similaritySearch(...)
```

按店铺过滤：

```text
FilterExpressionBuilder.eq("shopId", shopId)
```

### 6.4 关键词抽取

`extractKeywords` 是轻量业务抽取，不是通用分词器：

- 去除标点和常见停用词。
- 长度短时整串作为关键词。
- 生成连续二元组。
- 对“会员/SVIP/福利/积分/秒杀/退款/优惠券”等业务词额外补充。

这套方案解决“明明知识库有 SVIP 规则，但向量弱召回答不上来”的问题。

## 7. Milvus 配置与兼容

`CommonConfiguration`：

- 手动创建 `MilvusServiceClient`。
- 如果 database 不是 default，先用管理连接创建数据库。
- 检查已有 collection 的向量维度，和配置不一致时：
  - `initialize-schema=true`：drop 后重建。
  - `initialize-schema=false`：抛异常，避免隐式破坏。
- 三个 `VectorStore` Bean 使用同一客户端、同一 EmbeddingModel。
- `BatchingStrategy` 固定每批 10 条，避免一次 embedding 请求过大。

面试可扩展：

- Milvus 是分布式向量数据库，支持 collection、分区、索引。
- Spring AI `VectorStore` 统一了 `add/delete/similaritySearch` 接口。
- 本项目通过 metadata filter 实现“按店铺/按文档类型删除和过滤”。

## 8. 会话记忆

### 8.1 持久化

`DbConversationMemoryService`：

- `AiConversation`：会话标题、场景、状态、更新时间。
- `AiMessage`：`user` / `assistant` / `tool`。

### 8.2 上下文窗口

```java
loadRecentMessagesBefore(conversationId, beforeMessageId, windowSize)
```

取最近 `windowSize * 2` 条 `user` 和 `assistant` 消息，再反转成正序，转成 Spring AI `Message`。

关键点：

- `tool` 消息持久化，但不进入模型上下文。
- 当前消息 ID 之前的消息才加载，避免把本轮用户消息重复注入。
- `window-size` 默认 8。

### 8.3 前端短期状态

前端 `Pinia` 使用 `localStorage` 保存当前会话 ID 和聊天窗口，后端 MySQL 保存全量历史。这样切换会话时仍能从接口拉取历史。

## 9. SSE 流式输出

### 9.1 服务端

`ChatSseServiceImpl`：

```text
ConcurrentHashMap<StreamKey(userId, conversationId),
                 CopyOnWriteArrayList<SseEmitter>>
```

- `connect`：创建无超时 `SseEmitter`，注册到 map，发送 `connected`。
- `send`：遍历同一 key 的 emitter，按 event 类型发送。
- 完成/超时/异常时移除 emitter。

### 9.2 发送事件的位置

`ChatMessageCreatedEventListener` 是事件推送中枢：

- `queued`
- `status`
- `message`
- `answer_delta`
- `answer_done`
- `error`
- `done`

### 9.3 最终答案流式

`streamFinalAnswer`：

1. 如果 streaming 关闭或 `StreamingChatModel` 不存在，直接按固定 chunk 兜底发送。
2. 否则调 `streamingChatModel.stream(prompt)`。
3. 遍历 `ChatResponse`，提取 delta，发送 `answer_delta`。
4. 如果流为空或失败：
   - 已输出部分内容，按前缀补全。
   - 没输出内容，整体 fallback。

### 9.4 为什么选 SSE

- 当前场景是服务端单向持续推送。
- 浏览器原生 `EventSource`/`fetch` 支持。
- 相比 WebSocket 更轻量，不需要维护双向协议。

## 10. MCP 与外部工具扩展

`LocalLifeMcpService`：

- 通过 HTTP 调用外部 `/tools/invoke`。
- 请求体：`{ tool, arguments }`。
- 支持 `data` / `result` / `payload` 三种响应包装。
- 白名单：默认 `route_plan`、`weather_brief`。
- 超时：默认 2500ms。
- 默认 `ai.agent.mcp.enabled=false`，因此当前实际走本地 fallback。

本地 fallback：

- 路线：Haversine 公式估算距离，按距离给出步行/骑行/打车建议。
- 天气：按当前月份推断冷/暖季节，给出用餐建议。

这体现了“外部工具可接入、可降级、可测试”的工程思路。

## 11. 可观测性

Agent Loop 通过：

- `AgentLoopTrace`：记录每步状态、细节和工具名。
- `ToolExecutionLog`：记录工具返回 payload（截断到 1200）。
- MySQL `tool` 消息：`appendToolMessage` 持久化工具摘要。
- SSE `status`：实时推送给前端。

这些让 Agent 不再是一个黑盒。

## 12. 面试一句话总结

这是一个“手写 Think-Execute 循环 + Spring AI ToolCallback + Milvus 三向量 RAG + MySQL 记忆 + SSE 流式”的多轮 Agent。模型负责决策，代码负责工具注册、工具执行、循环边界、日志和 SSE 状态推送。

