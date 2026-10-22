# 商城助手 mall-agent 设计文档

## 1. 意图与背景

用 Spring AI 做一个**生产级**的电商智能助手,能做商品推荐、订单查询、售后/规则问答。

使用者的真实目的:**面试时能讲清楚每个知识点**。因此:
- 每个知识点必须是真实、可运行、可指给面试官看的模块,不是玩具。
- 代码要**简洁**,作者自己读完能完整讲清楚一次请求从进来到返回的整条链路。
- 后端为主,前端只要一个能输入问答的简单页面。

覆盖的知识点(验收清单):
1. RAG:文档解析、切分、检索不准的优化(Query 改写、召回、混合检索、重排)、资料更新维护
2. Skill 与工具调用:Tool 设计、调用失败的处理(参数错、接口超时)、Skill 搭建
3. 架构选型:单 Agent vs 多 Agent
4. 记忆与上下文:短期记忆、长期记忆(用户偏好)、历史过长的处理
5. 评测与迭代:评测标准、可观测

## 2. 目标 / 非目标 / 反目标

**目标**
- 以上 5 类知识点各有对应模块,并在 README 里写"面试讲法"(为什么这么设计、替代方案、取舍)。
- 一条 `docker compose up` + 一个启动命令即可跑起来。
- 评测可一键运行并输出对比报告,能体现"改一处 → 指标变化"。

**非目标**
- 不做真实支付、下单、库存;不做登录体系(用请求头模拟 userId)。
- 不做复杂前端(无框架,单个 HTML 页)。
- 不做多租户、分布式部署、K8s 配置。

**反目标(技术上能跑但算失败)**
- 抽象层过多、读不懂的"框架式"代码;为设计模式而设计模式。
- 把 RAG/记忆/工具全塞进一个大类,讲不清链路。
- 评测只有口头描述、没有数据集和数字。
- 密钥写死在代码里。

## 3. 技术栈(以下为构建者的决定,非用户指定,除标注外)

| 项 | 选择 | 来源 |
|---|---|---|
| 语言/框架 | Java 21、Spring Boot 3.4、Spring AI 1.0.x、Maven | 我定 |
| 大模型 | 通义千问(DashScope),模型名、key、base-url 全在 `application.yml`,key 走环境变量 | **用户指定** |
| 接入方式 | DashScope 的 OpenAI 兼容端点,使用 Spring AI 官方 OpenAI starter | 我定(减少第三方依赖) |
| 向量库 | Qdrant | **用户指定** |
| Embedding / Rerank | 千问 `text-embedding-v3`、`gte-rerank`(rerank 用 RestClient 直接调 DashScope REST,Spring AI 无标准抽象) | 我定 |
| 业务数据 | MySQL(商品、订单、用户偏好、对话摘要) | 我定 |
| 会话缓存 | Redis(短期记忆) | 我定 |
| 容错 | Resilience4j(超时、重试、熔断) | 我定 |
| 可观测 | Spring AI 自带 Micrometer observation + Actuator/Prometheus 端点 + 结构化日志 | 我定 |
| 部署 | docker-compose 起 MySQL、Redis、Qdrant;测试用 H2 + 内嵌/Mock | 我定 |
| 前端 | 单个静态 HTML + 原生 JS,SSE 流式输出 | **用户指定"简单前端"**,形式我定 |

## 4. 总体架构:Router + 专家 Agent(我定)

```
用户消息 ──► ChatController(SSE)
              │  取 userId、sessionId
              ▼
        MemoryService.load()      ← 短期记忆(Redis 窗口 + 摘要)+ 长期偏好(MySQL)
              ▼
        RouterAgent               ← 只做意图分类,输出 RECOMMEND / ORDER / KNOWLEDGE / CHITCHAT
              ▼
   ┌──────────┼───────────┐
 推荐专家    订单专家     知识库专家
 (商品检索   (订单/物流   (RAG 检索
  工具+Skill) 工具+Skill)  +Skill)
   └──────────┼───────────┘
              ▼
        MemoryService.save()      ← 追加消息;超长则摘要;异步抽取偏好
              ▼
           流式返回
```

**为什么多 Agent**:工具与提示词互不污染;权限隔离(推荐专家拿不到订单工具);可分别评测。
**对照开关**:配置项 `agent.mode=multi|single`,single 时一个 Agent 挂全部工具,评测可对比两种模式的工具选择准确率与 token 成本,用于面试回答"为什么不是单 Agent"。
**CHITCHAT** 由 Router 直接回复,不进专家。

## 5. RAG 模块

### 5.1 知识库文档(模拟数据,放在 `src/main/resources/kb/`)
覆盖电商常见规则类/说明类文档,格式混合 PDF、Word、Markdown:
售后政策(退换货、退款时效、发票)、配送物流(范围、时效、运费表)、会员与营销(等级权益、积分、优惠券叠加规则)、支付与账户、品类选购指南(含尺码对照表)、客服 FAQ、平台活动规则。
**必须故意包含的难点样本**(供评测使用):
- 条款含例外("7 天无理由,但定制/贴身类除外")
- 同一政策的新旧两个版本(旧版已过期)
- PDF/Word 中的表格(运费模板、尺码表)
- 口语问法与书面语差异(用户问"能退吗",文档写"七日无理由退货")

### 5.2 摄入链路 `IngestionService`
解析(Spring AI Tika Document Reader)→ 清洗 → 切分 → 元数据 → 向量化 → 写入 Qdrant。
- **切分**:Markdown/Word 按标题层级切;FAQ 按问答对整条一块;表格整体作为一块并转成 Markdown 文本;长条款用**父子块**(子块检索、命中后返回父块给模型)。
- **元数据**:`docId, docVersion, category, title_path, effective, updatedAt, contentHash`。

### 5.3 检索链路 `RetrievalService`
1. **Query 改写**:用 LLM 做指代消解(结合历史)+ 口语转规范表述 + 生成 2~3 个扩展 query(Multi-Query)。
2. **混合召回**:Qdrant 同一 collection 存稠密向量与稀疏(BM25 风格)向量,各召回 top-N,用 **RRF** 融合。稀疏向量的生成方式(本地词频/分词实现)由构建者决定,须可离线运行。
3. **元数据过滤**:默认只检索 `effective=true` 的版本;按路由出的 category 缩小范围。
4. **重排**:对融合后候选调用 rerank 模型取 top-K。
5. **父块展开 + 引用**:返回父块文本及来源(文档名、标题路径),回答须带引用。
6. **拒答**:重排最高分低于阈值时,明确告知"知识库中没有相关信息",不得编造。

每一步可通过配置开关单独关闭,用于评测中做**消融对比**(只稠密 / +稀疏 / +改写 / +重排)。

### 5.4 更新维护
- 管理接口:`POST /admin/kb/upload`(上传/更新文档)、`DELETE /admin/kb/{docId}`、`POST /admin/kb/reindex`。
- **增量更新**:按文档 `contentHash` 判断是否变化;变化则删除该 `docId` 旧块再写入新块,未变化跳过。
- **版本管理**:新版本上线时,旧版本块标记 `effective=false`(保留可回溯),检索默认过滤。
- 启动时扫描 `kb/` 目录做一次增量同步。

## 6. 工具与 Skill

### 6.1 工具设计(Spring AI `@Tool`)
| 工具 | 归属 | 说明 |
|---|---|---|
| `searchProducts(keyword, category, minPrice, maxPrice, brand)` | 推荐专家 | 查 MySQL 商品;可附带结合用户偏好的排序 |
| `getProductDetail(productId)` | 推荐专家 | |
| `getOrder(orderNo)` / `listMyOrders(status)` | 订单专家 | |
| `getLogistics(orderNo)` | 订单专家 | 调"物流服务"(用一个会随机慢/失败的 Mock 服务模拟外部接口) |
| `searchKnowledge(query)` | 知识库专家 | 走 5.3 的检索链路 |

设计原则(README 里讲清楚):
- 描述写清"何时用、何时不用",参数用枚举和明确范围。
- **`userId` 永远不作为模型可传参数**,从会话上下文取,防止越权查他人订单;订单工具在服务层再校验归属。
- 返回精简的结构化结果,不返回整表/无用字段,节省 token。

### 6.2 工具调用失败处理
统一把工具异常转换为**结构化错误结果**回给模型(不抛到上层中断对话),带 `errorCode / message / retryable / hint`:
- **参数错误**(如订单号格式不对、枚举值非法):后端校验失败 → 返回错误和修正提示 → 模型自我纠正重试,**最多 N 次**(配置),超出则向用户澄清而不是死循环。
- **接口超时/下游失败**:Resilience4j 超时 + 有限重试(仅幂等读操作)+ 熔断;最终降级为"物流信息暂时无法获取,订单状态为 X",不编造物流轨迹。
- **越权/不存在**:明确返回"未找到该订单",不泄露他人订单是否存在。
- 每次工具调用记录:工具名、参数(脱敏)、耗时、成败、重试次数。

### 6.3 Skill
Skill = 目录下的 `SKILL.md`(frontmatter 含 name、description,正文为流程/话术/注意事项),放 `src/main/resources/skills/`。
- **渐进披露**:系统提示词里只放各 Skill 的 name+description 目录;模型通过 `loadSkill(name)` 工具按需加载正文,避免所有流程常驻上下文。
- 至少提供:`return-and-refund`(退换货办理流程)、`order-troubleshooting`(订单/物流异常排查)、`product-recommendation`(推荐话术与追问策略)。
- 每个专家只能加载与自己相关的 Skill。

## 7. 记忆与上下文

- **短期记忆**:Redis 按 sessionId 存近期消息,滑动窗口(按条数与 token 估算双限制)。
- **历史过长**:超过阈值时把较早的消息用 LLM 压缩为**摘要**,保留"摘要 + 最近 N 轮";摘要中必须保留订单号、商品、用户已作出的决定等关键事实。
- **长期记忆(用户偏好)**:每轮对话结束后**异步**用 LLM 抽取偏好(品牌、预算、尺码、风格等,带置信度与时间),upsert 到 MySQL;新会话开始时把偏好以简短文本注入推荐专家。用户可通过接口查看/删除偏好(`GET/DELETE /memory/preferences`)。
- 明确区分并在 README 说明:短期(会话内)/ 摘要(会话内压缩)/ 长期(跨会话)三层各自的存储、写入时机、读取时机。

## 8. 评测与可观测

### 8.1 评测
- 评测集放 `src/test/resources/eval/*.jsonl`,按模块分:
  - **检索集**:query → 期望命中的 docId/标题路径,含上述难点样本。指标:Recall@k、MRR,以及"拒答正确率"(知识库无答案的问题)。
  - **路由集**:query → 期望意图。指标:准确率。
  - **工具集**:query(+预置场景)→ 期望工具及关键参数。指标:工具选择准确率、参数正确率。
  - **端到端集**:问答对。指标:答案忠实度/正确性(LLM-as-Judge,给出评分标准 rubric)、是否带引用。
- 一键运行(Maven profile 或 CLI 入口)→ 输出 Markdown/JSON 报告,含各指标和失败用例,并可与上一次结果对比。
- 支持检索消融(见 5.3)与 single/multi 模式对比。
- 评测集规模由构建者决定,但每类难点样本至少数条,保证指标有区分度。

### 8.2 可观测
- 每次请求一个 `traceId`,串联:路由结果 → 改写后 query → 召回数量 → 重排分数 → 工具调用(耗时/成败/重试)→ token 用量 → 总耗时。
- Micrometer 指标:请求耗时、各阶段耗时、工具成功率、token 用量、拒答次数;Actuator 暴露。
- 日志结构化(JSON 或 key=value),敏感信息脱敏。

## 9. 代码风格与结构约束

- 包按职责分:`api`(controller)、`agent`(router/专家)、`rag`、`tool`、`skill`、`memory`、`eval`、`config`。
- 一个类一件事,方法短;少接口少继承;注释写"为什么"而非"做什么";关键链路类顶部用几行注释说明本步在整条链路中的位置。
- 配置集中在 `application.yml`(模型、阈值、开关、重试次数、窗口大小)。
- README 包含:架构图、一次请求的完整链路走读、五大知识点的"面试讲法"、启动与评测命令。

## 10. 交付物

1. 可运行的 Spring Boot 工程(`D:\ideaWorkspace\mall-agent`)+ `docker-compose.yml`
2. 模拟商品/订单数据(SQL 初始化)与模拟知识库文档
3. 单页聊天前端
4. 评测集与评测运行器、报告示例
5. README(含面试讲法)
6. 自动化测试:工具校验与容错、切分与摄入增量更新、RRF 融合、记忆压缩等核心逻辑的单测(LLM 调用处用 Mock)

## 11. 留给构建者决定

稀疏向量实现细节、各阈值与窗口默认值、数据库表结构细节、Mock 数据具体内容、评测集题目内容、前端样式、具体 Spring AI 1.0.x 版本号与 API 细节。

## 12. 评审后补充的决定(均为构建者的决定,非用户指定,除标注外)

- **Qdrant**:Spring AI 的 `QdrantVectorStore` 只支持单稠密向量,所以摄入与检索直接用 `qdrant-client`(命名向量 dense + sparse,Query API 的 RRF fusion)。`VectorStore` 抽象不用于本项目。计划第一步做一个小验证:千问 embedding(批量上限、维度)、Qdrant 混合查询、OpenAI starter 对千问的工具调用与流式。
- **稀疏向量**:纯 Java 实现,中文用字二元组(bigram)加英文数字整词,哈希为稀疏索引,词频做权重;不引入分词库。
- **Router**:每次请求一次独立的轻量 LLM 调用(模型可在 yml 单独配置),用结构化输出 `{intent, category}`,category 供知识库专家做元数据过滤;只看摘要和最近几轮。多意图消息只处理主意图,其余提示用户分开问。SSE 只流式输出专家的回答。
- **userId**:请求头 `X-User-Id`,缺失返回 401;通过 Spring AI `ToolContext` 传入工具,不用 ThreadLocal。
- **知识库文件**:用一个脚本(PDFBox/POI)生成 PDF 与 Word,生成结果提交进仓库。版本用文件名约定 `<docId>__v<N>`;同一 docId 启动时版本最高者 `effective=true`。`reindex` 忽略 contentHash,强制重建。表格:Word 用 POI 自行提取为 Markdown;PDF 仅做尽力而为的文本提取,带表格的核心样本以 Word/Markdown 为主。
- **工具重试上限**:用自定义 `ToolCallback` 包装,按请求计数,不使用裸 `@Tool` 自动注册。
- **token 统计**:依赖响应 usage(流式请求设置 `stream_options.include_usage`),拿不到时用字符数估算并标明。
- **Admin 接口**:请求头 `X-Admin-Token`,值来自环境变量。
- **记忆共享**:同一 session 共用一份历史;偏好只注入推荐专家。
