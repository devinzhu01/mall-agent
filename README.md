# 商城助手 mall-agent

基于 Spring AI + 通义千问 + Qdrant 的电商智能助手后端:**商品推荐、订单/物流查询、售后与平台规则问答**。
定位是"面试能讲清楚每个知识点的生产风格项目",所以每个知识点都对应一个真实可运行的模块,下面的"面试讲法"逐一说明怎么做、为什么、替代方案和常见追问。

- 设计文档:`docs/superpowers/specs/2026-10-22-mall-agent-design.md`
- 实施计划:`docs/superpowers/plans/2026-10-22-mall-agent.md`

## 1. 快速开始

**环境**:JDK 17+(Spring Boot 3.4)、Maven、Docker(Qdrant / Redis,MySQL 若已有本机实例可不用 Docker)。

```bash
# 1. 依赖服务(MySQL 已有的话只启动 redis 和 qdrant:  docker compose up -d redis qdrant)
docker compose up -d

# 2. 建表和模拟数据:表结构 src/main/resources/schema.sql,模拟商品/订单 src/main/resources/data.sql
#    (MySQL 手工执行一次;data.sql 重复执行会主键冲突)

# 3. 环境变量
export DASHSCOPE_API_KEY=sk-xxx        # 千问 key
export DB_USER=root DB_PASSWORD=xxx    # MySQL 账号密码
export ADMIN_TOKEN=<自己生成的随机串>   # /admin/* 接口令牌;不配置或为 change-me 时管理接口一律拒绝

# 4. 启动(首次启动会把 src/main/resources/kb 下的文档向量化写入 Qdrant,之后文件没变就跳过)
mvn spring-boot:run
# 打开 http://localhost:8080 ,右上角用户填 u1001(有 4 个订单)或 u1002
```

模型、key、阈值、开关全部在 `application.yml`(`spring.ai.openai.*` 是千问的 OpenAI 兼容端点;`agent.*` 是业务参数)。

**常用命令**

| 目的 | 命令 |
|---|---|
| 全部单测 | `mvn test` |
| 验证千问/Qdrant 可用(需 key 和 Qdrant) | `mvn test -Dtest=SpikeLiveTest,KbStoreLiveTest` |
| 跑评测 | `mvn spring-boot:run -Dspring-boot.run.profiles=eval`(结果在 `eval-reports/`) |
| 只跑评测的某几部分 | 追加 `-Dspring-boot.run.arguments=--eval.sections=retrieval,routing` |
| 切到单 Agent 模式 | `agent.mode: single` |
| 上传/更新知识库文档 | `curl -H "X-Admin-Token: $ADMIN_TOKEN" -F file=@return-policy__v3.md -F category=售后 localhost:8080/admin/kb/upload` |

接口:`POST /chat`(头 `X-User-Id`,体 `{"sessionId","message"}`,SSE 流式)、`GET|DELETE /memory/preferences`、`/admin/kb/*`、`/actuator/prometheus`。

## 2. 架构

```
POST /chat ──► ChatController           校验:X-User-Id(401)、message 非空且≤2000字、sessionId≤64字(400)
                  │
                  ▼
            AgentOrchestrator.open()    ← 一次请求的总流程,读懂这个类就读懂了链路
                  │ 1. MemoryService.load        摘要(MySQL) + 最近消息(Redis,条数+字符数双限制) + 用户偏好(MySQL)
                  │ 2. RouterAgent.route         小模型一次结构化输出 {intent, category, reply};失败降级 KNOWLEDGE
                  │ 3. ExpertFactory.forIntent   装配专家 = 提示词 + 工具 + 可加载的 Skill
                  │ 4. ToolBox.build             工具包 GuardedToolCallback(异常→结构化错误、失败预算)
                  │ 5. ExpertChat.stream         流式回答;Spring AI 内部完成"选工具→执行→回灌"循环
                  ▼
        ┌─────────┼──────────┐
   推荐专家     订单专家     知识库专家          (每个专家只拿自己的工具;CHITCHAT 由 Router 直接回复)
 ProductTools  OrderTools   KnowledgeTools
 + Skill       LogisticsTools  → RetrievalService: 改写→双路召回→RRF→过滤→重排→拒答
                  │
                  ▼ 流结束后
            Tracer.finish() + MemoryService.save (异步:压缩摘要、抽取偏好)
```

**一次请求的链路走读**(以"订单 2026100100000001 到哪了"为例):
1. `UserIdFilter` 校验头;`ChatController` 校验入参。
2. `MemoryService.load` 取摘要/最近消息/偏好,Redis 挂了只会降级为"无记忆"。
3. `RouterAgent` 判出 `ORDER`;`ExpertFactory` 给订单专家装上 `OrderTools + LogisticsTools + KnowledgeTools + SkillTools`。
4. 模型决定调用 `getLogistics(orderNo)`;`GuardedToolCallback` 执行:`userId` 来自 `ToolContext`(不是模型参数),先校验订单归属,再调物流接口(超时/重试/熔断,失败则降级为"暂时无法获取")。
5. 结果回灌模型,流式输出;SSE 依次发 `trace`(traceId)→ `message`(多段)→ `done`。
6. 流结束:`RequestTracer.finish()` 打一行结构化日志(`traceId route tools tokens ms`),异步落记忆。

## 3. 知识点与面试讲法

### 3.1 RAG

**怎么做**(代码都在 `rag/`)
- **解析** `DocParser`:md 按 `#` 层级、docx 按 Heading 样式识别标题链;**表格单独成块并转 Markdown**(拆开会丢表头);FAQ 一问一答一块;PDF 用 Tika 尽力提取(不保证表格)。
- **切分** `DocChunker`:按标题结构切而不是固定长度;长条款按句子切成子块,**子块检索、父块回答**(命中"例外情况"这一小段,但交给模型的是完整小节);块 ID 由 `docId|version|序号` 确定,重复摄入幂等。
- **检索** `RetrievalService`(每步都是 `agent.rag.*` 开关,评测里做消融):
  1. **Query 改写**:`QueryRewriter` 一次 LLM 调用,做指代消解("它能退吗"→"羽绒服能退吗")、口语转书面("能退吗"→"是否支持七日无理由退货")并扩展 2~3 个问法。
  2. **混合召回**:Qdrant 同一 collection 放两路向量,`dense`(千问 text-embedding-v3,语义)和 `sparse`(`SparseEncoder`:中文字二元组 + 英文整词的 BM25 式关键词信号,纯 Java 离线),分别召回。
  3. **RRF 融合**:`Σ 1/(60+rank)`,只看名次不看分数,所以余弦分和词频分量纲不同也能直接合并。
  4. **元数据过滤**:默认只查 `effective=true`(旧版本不泄漏);Router 给的类别做过滤,**过滤后查不到会放开类别重查**(Router 可能判错)。
  5. **重排**:`Reranker` 调 gte-rerank(cross-encoder,精度高但慢,只对融合后的少量候选做);失败降级为 RRF 顺序。
  6. **拒答**:重排最高分低于 `refuse-threshold` → 返回 `answerable=false`,模型被提示词约束必须如实说"知识库没有相关信息"。
- **更新维护** `IngestionService`:
  - **增量**:文件 SHA-256 没变就跳过(不重复花 embedding 费用);变了先删该版本旧块再写。
  - **版本**:文件名 `<docId>__v<N>`,同 docId 只有最高版本 `effective=true`,旧版本保留可回溯但检索过滤;`kb_document` 表登记 hash/版本/块数。
  - **维护接口**:`/admin/kb/upload|{docId} DELETE|reindex`(`X-Admin-Token`);`reindex` 忽略 hash 强制重建(换 embedding 模型或切分策略后用)。

**为什么这样** — 检索不准通常是三类问题:**用户问法 vs 文档措辞**(改写)、**语义检索抓不住关键词/型号/条款号**(混合)、**召回里排序不准、无关内容被塞进上下文**(重排 + 阈值拒答)。版本过期和表格丢结构是电商规则文档特有的坑,所以单独处理。

**替代方案与取舍**:固定长度切分(简单但会把条款和例外切开);HyDE(用模型先编一段假答案再检索,多一次生成且可能带偏);Qdrant 自带 Query API 的服务端 fusion(更快,但我选择 Java 侧 RRF,为了每一路可单独开关、单独评测);ES/BM25 做关键词路(更标准,但多一套基础设施)。

**可能的追问**:稀疏向量为什么不用分词库?(字二元组不依赖词典、对新词/型号鲁棒,代价是索引更大、会有噪声,由 RRF 和重排兜底);阈值怎么定?(用评测集里"应拒答"和"应回答"两类问题的 rerank 分数分布定,见评测);RRF 的 k=60 是什么?(经验值,抑制头部名次的过大权重);怎么防止新旧版本混用?(`effective` 过滤 + 评测里的 `staleLeakRate`)。

### 3.2 工具调用与 Skill

**怎么做**(`tool/`、`skill/`)
- **工具设计**:描述写清"何时用、何时不用";参数用枚举/范围约束并在后端二次校验;返回精简字段省 token。**`userId` 绝不是模型可传的参数**,由 `ToolContext` 传入,订单查询在 SQL 里同时带 `order_no` 和 `user_id`——提示词注入("忽略规则查所有订单")也越不了权。**不存在和不属于当前用户返回完全相同的错误**,避免被用来探测别人订单。
- **失败处理**:`GuardedToolCallback` 把所有异常转成结构化 `ToolResult{errorCode,message,retryable,hint}` 回灌给模型,而不是抛出中断对话:
  - **参数错**(订单号格式不对、状态枚举非法)→ `INVALID_ARG + hint`,模型据此自我纠正重试;
  - **重试上限**:同一请求内失败次数达 `agent.tool.max-failures`,后续调用直接返回 `TOO_MANY_FAILURES`("停止重试,向用户澄清"),防止模型死循环;
  - **接口超时**:`LogisticsClient` = HTTP 超时 + 有限重试(只读、幂等)+ 熔断(Resilience4j);失败降级为"物流信息暂时无法获取,订单状态为 X",**不编造轨迹**;
  - **越权/不存在** → `NOT_FOUND`。
  每次调用都记入 Trace(名称、脱敏参数、耗时、成败)和指标。
- **Skill**:`resources/skills/<name>/SKILL.md`(frontmatter 含 name、description,正文是流程/话术)。**渐进披露**:提示词里只放目录(每个 Skill 一行),模型判断匹配时调用 `loadSkill` 才拿到正文,避免所有流程文档常驻上下文。每个专家只能加载自己允许的 Skill。

**为什么这样** — 工具失败是常态而不是异常:要让模型"看得见"失败原因并选择下一步;重试要有预算;对外部接口要假设它会慢会挂。Skill 解决"流程知识很多但每次只用一小部分"的上下文成本问题。

**替代方案与取舍**:把流程直接写进系统提示词(简单,但 token 常驻且互相干扰);用 MCP 暴露工具(跨应用复用更好,本项目只有一个应用,直接 `@Tool` 更简单);工具异常直接抛出由框架重试(丢失"提示模型怎么改"的机会)。

**可能的追问**:为什么重试限制放在 ToolCallback 而不是框架?(Spring AI 的工具循环是内部的,包装回调是最小侵入的计数点);模型一直传错参数怎么办?(预算耗尽后让它向用户澄清);写操作怎么办?(本项目只有读工具;写操作需要幂等键 + 用户确认,不能自动重试)。

### 3.3 单 Agent 还是多 Agent

**怎么做**:Router + 三个专家(推荐/订单/知识库)。`agent.mode=single` 时跳过 Router,一个 Agent 挂全部工具与 Skill——**用来评测对比**。

**为什么多 Agent**:① 工具与提示词互不污染(工具少,模型选得准,也省 token);② **权限隔离**(推荐专家拿不到订单工具,不靠提示词约束);③ 可以分别评测、分别换模型(Router 用便宜的小模型 `qwen-turbo`)。

**代价与诚实的取舍**:Router 多一次模型调用(延迟和成本),路由错误会放大(所以 Router 失败降级到只读的知识库专家,类别过滤也有兜底);多意图的一句话只处理主意图。工具总数少于十来个、意图边界模糊时,单 Agent 更简单更好——**评测里 `tools.multi.*` vs `tools.single.*`(选择准确率、平均 token、耗时)就是用来回答"到底值不值"的数据**,而不是凭感觉。

**可能的追问**:什么时候该合并回单 Agent?(工具数少、路由准确率低于收益时);专家之间怎么共享上下文?(同一 session 共用一份历史,偏好只注入推荐专家);为什么不用 Supervisor 反复调度?(电商场景意图通常单一,一次路由足够,Supervisor 多轮调度成本高)。

### 3.4 记忆与上下文

三层,各管一件事(`memory/`):

| 层 | 存储 | 写入时机 | 读取时机 |
|---|---|---|---|
| 短期 | Redis List `chat:{sessionId}`(带 TTL) | 每轮回答结束 | 每次请求,按**条数和字符数双限制**取最新窗口 |
| 摘要 | MySQL `conversation_summary` | 历史超过 `summarize-threshold` 条时压缩 | 每次请求,作为背景注入 |
| 长期(偏好) | MySQL `user_preference` | 每轮后**异步**用 LLM 抽取 | 新会话开始,只注入推荐专家 |

- **历史太长**:超阈值后把"较早消息 + 旧摘要"压成新摘要,只留最近 N 条原文;**摘要提示词强制保留订单号、商品、用户的决定、未解决的问题**,否则压缩后模型会"忘"关键事实。压缩失败退化为硬截断,不让请求失败。
- **偏好抽取**:只收白名单 key(brand/budget/size/style/category)和置信度 ≥ 0.5;唯一键 `(user, key, value)` 幂等 upsert;用户可 `GET/DELETE /memory/preferences` 查看和清除。
- **偏好只作参考**:提示词里写明"与用户本轮明确说的需求冲突时以本轮为准"。

**可能的追问**:为什么偏好异步抽取?(不占回答响应时间,失败也不影响主流程);偏好有隐私风险怎么办?(白名单字段、可查看可删除、不存敏感信息);Redis 挂了怎么办?(记忆是增强不是必需,降级为无记忆);摘要会不会越滚越偏?(每次都以"旧摘要 + 新增对话"重写并强约束保留事实,评测里可加多轮用例观察)。

### 3.5 评测与可观测

**评测**(`eval/`,数据集在 `src/test/resources/eval/*.jsonl`,各 ≥ 20 条):

| 评测 | 指标 | 说明 |
|---|---|---|
| retrieval | Recall@4、MRR、contentHit、staleLeakRate、falseRefusalRate、refusalAccuracy | **消融四档**:只稠密 → +稀疏 → +改写 → +重排(完整);含四类难点(条款例外、新旧版本、表格、口语问法)和库外问题 |
| routing | 意图准确率 | 4 类意图 |
| tools | 工具选择准确率、参数正确率、平均 token、平均耗时 | **multi vs single 两种架构对比** |
| e2e | correctness / faithfulness(1-5)、citationRate、passRate | LLM 当裁判,rubric 写在 `LlmJudge` 提示词里,温度 0 |

- 评测用 `ChatCache` 缓存改写/路由/裁判的模型响应(只在 eval 配置存在),重复评测不重复花钱,**指标变化来自你改的东西而不是模型随机波动**。
- 每次输出 `eval-reports/<时间>.md/.json`,自动和上一份**逐指标对比(Δ)**,失败用例单独列出。
- 数据集本身有测试(`EvalDatasetsTest`):数量、难点覆盖,以及 `mustContain` 确实出现在期望文档里——避免评测冤枉系统。
- 迭代方式:改一个开关/提示词/阈值 → 再跑评测 → 看 Δ。

**可观测**(`trace/`):
- 每个请求一个 `traceId`,串起:路由结果 → 改写后的 query → 召回/重排分数 → 工具调用(耗时/成败)→ token → 总耗时;流的第一个 SSE 事件把 traceId 给前端。
- Micrometer 指标:`agent.stage.duration{stage}`、`agent.tool.calls{tool,ok}`、`agent.tokens{type}`、`agent.rag.refusals`,经 `/actuator/prometheus` 暴露。
- 结构化日志一行 `traceId=… route=… tools=[…] tokens=… ms=…`;订单号只留后 4 位、手机号中间打码。
- token 取自响应的 usage(流式需要 `stream_options.include_usage`,已设置);取不到时按字符数估算并在 Trace 里标 `estimated`。

**可能的追问**:LLM 裁判可靠吗?(用固定 rubric + 温度 0 + 参考要点,并抽查失败用例;裁判与被测同一模型有偏向风险,可用 `agent.eval.judge-model` 换更强的);评测集怎么避免过拟合?(难点样本和库外问题要持续从线上失败案例补充,不能只为通过而调);线上怎么发现问题?(拒答率、工具失败率、token 与耗时的指标和告警)。

## 4. 目录结构

```
src/main/java/com/mall/agent/
  api/      ChatController, AdminKbController, PreferenceController, UserIdFilter, AdminTokenFilter, GlobalExceptionHandler
  agent/    AgentOrchestrator, RouterAgent, ExpertFactory, ExpertChat, Expert, Intent, RouteDecision
  rag/      DocParser, DocChunker, SparseEncoder, KbStore(Qdrant), Rrf, QueryRewriter, Reranker, RetrievalService, IngestionService, KbSyncRunner
  tool/     GuardedToolCallback, ToolBox, ToolResult, ProductTools, OrderTools, LogisticsTools(+Client/Mock), KnowledgeTools, SkillTools
  skill/    SkillRegistry
  memory/   MemoryService, ShortTermMemory, HistoryCompactor, PreferenceExtractor
  llm/      Llm(内部非流式调用:改写/路由/摘要/偏好/裁判)
  trace/    RequestTracer, TraceService, Masker
  eval/     EvalRunner, LlmJudge, ChatCache, ReportWriter, EvalMetrics
  data/     各 Repo(JdbcClient)
src/main/resources/  application.yml, schema.sql, data.sql, kb/(模拟知识库), skills/, static/index.html
src/test/resources/eval/   四个评测集
```

## 5. 模拟知识库

`src/main/resources/kb/<类别>/<docId>__v<N>.<md|docx|pdf>`,覆盖售后、物流、会员营销、选购指南、FAQ、支付账户、活动规则,并**故意包含**评测用的难点:条款例外(`return-policy__v2.docx`)、新旧版本冲突(v1 的"7 个工作日"已过期)、docx/md 表格(运费模板、尺码表、会员等级)、口语问法(FAQ)、PDF(`promo-2026__v1.pdf`)。docx/pdf 由 `src/test/java/.../tools/GenerateKbDocs.java` 生成。

## 6. 已知限制与未验证项

- **本仓库交付时没有用真实千问 key / Qdrant / Redis 跑过端到端和评测**(开发环境没有 key 和 Docker)。所有 LLM/Qdrant/Redis 交互都经过单测(Mock)和 Spring 上下文装配测试,但这些需要你首次运行时确认:
  1. `mvn test -Dtest=SpikeLiveTest,KbStoreLiveTest`(需 `DASHSCOPE_API_KEY` 和本地 Qdrant 6334):验证千问的对话/embedding 批量上限/工具调用,以及 Qdrant 命名向量 + 稀疏向量查询。embedding 批大小默认 10(`agent.rag.embedding-batch-size`),若千问实际上限不同请调整。
  2. 启动后观察日志 `kb sync done`;浏览器里分别试推荐/订单/规则三类问题。
  3. 跑一次评测拿到真实指标,再据此调 `refuse-threshold` 等参数。README 里没有写任何指标数字,是因为还没有真实数据。
- 物流接口是本应用内的 Mock(`/mock/logistics`,按概率变慢或失败),用来演示容错;eval 时需要 8080 端口空闲。
- 没有登录体系:`X-User-Id` 应由网关在认证后注入,不能由前端随意填写。
- PDF 只做文本提取,不保证表格结构;带表格的文档请用 docx/md。
- 多意图的一句话只处理主意图。
