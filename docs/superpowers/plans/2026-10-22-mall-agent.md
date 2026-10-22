# 商城助手 mall-agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个面试级、生产风格的电商助手后端(商品推荐、订单查询、规则问答),五大知识点(RAG、工具与 Skill、多 Agent、记忆、评测可观测)各有可讲解的真实模块。

**Architecture:** Router(一次轻量 LLM 结构化输出)分发给三个专家 Agent(推荐/订单/知识库),专家各挂自己的工具和 Skill;RAG 为"改写 → 稠密+稀疏双路召回 → Java 侧 RRF → 元数据过滤 → rerank → 阈值拒答";记忆三层(Redis 窗口、摘要、MySQL 偏好);所有 LLM 步骤都由统一 Trace 记录,评测运行器复用同一套服务。

**Tech Stack:** Java 21, Spring Boot 3.4, Spring AI 1.0.x(OpenAI starter 指向 DashScope 兼容端点), `io.qdrant:client`, Spring `JdbcClient` + MySQL 8, Redis, Resilience4j, Micrometer/Actuator, Apache Tika/POI/PDFBox, JUnit5 + AssertJ + Mockito, H2(测试)。

**Spec:** `docs/superpowers/specs/2026-10-22-mall-agent-design.md`(第 12 节为评审后补充的决定,与正文同等有效)

## Global Constraints

- 包根 `com.mall.agent`,子包:`api, agent, rag, tool, skill, memory, eval, config, trace`。
- 模型名、key、base-url、各阈值/开关/窗口/重试次数全在 `application.yml`;key 与 DB 密码走环境变量(`DASHSCOPE_API_KEY`, `DB_USER`, `DB_PASSWORD`, `ADMIN_TOKEN`),代码里不得出现明文。
- 表结构以用户已建的 6 张表为准:`product, orders, order_item, user_preference, conversation_summary, kb_document`(列名见设计对话中的 DDL,放入 `src/main/resources/schema.sql` 供测试/初始化用,字段保持不变)。
- `userId` 只来自请求头 `X-User-Id`(缺失 → 401),经 `ToolContext` 传给工具;任何工具的参数中不得出现 userId。
- `/admin/*` 需请求头 `X-Admin-Token`,值来自 `ADMIN_TOKEN`。
- KB 文件路径 `src/main/resources/kb/<category>/<docId>__v<N>.<md|docx|pdf>`;同 docId 版本最高者 `effective=true`。
- 稀疏向量:纯 Java,中文字二元组 + 英文数字整词,哈希为稀疏索引;不引入分词库。
- 检索默认只查 `effective=true`;rerank 最高分低于 `agent.rag.refuse-threshold` 时拒答,不得编造。
- 工具失败一律返回结构化 `ToolResult`(`errorCode/message/retryable/hint`),不向上抛;同一请求内工具失败次数超过 `agent.tool.max-failures` 时停止重试并让模型向用户澄清。
- 代码风格:一类一事、方法短、少接口少继承;注释写"为什么";链路关键类顶部用几行注释说明它在整条链路中的位置。
- LLM 调用处单测一律 Mock;依赖真实千问的只有评测运行器和 Task 1 的验证测试(用 `@EnabledIfEnvironmentVariable(named="DASHSCOPE_API_KEY", matches=".+")` 守卫)。

## Review Focus

1. 用户查询**别人的订单号**:返回与"订单不存在"相同的错误,不泄露存在性(Task 4)。
2. **物流接口超时/熔断打开**:返回降级结果,不编造轨迹(Task 4)。
3. **知识库为空或问题与库无关**:拒答并说明,不胡编(Task 8)。
4. **空消息 / 超长消息(>2000 字符) / 缺少 sessionId**:返回 400,不调用模型(Task 12)。
5. **重复上传同一文件、上传同 docId 新版本**:前者跳过,后者旧版本置为 `effective=false` 且检索不再命中(Task 7)。

---

## File Structure

```
pom.xml, docker-compose.yml, README.md
src/main/resources/
  application.yml, schema.sql, data.sql          # data.sql: 模拟商品/订单
  kb/<category>/<docId>__v<N>.*                  # 模拟知识库(Task 7 由脚本生成)
  skills/<name>/SKILL.md
  static/index.html
src/main/java/com/mall/agent/
  MallAgentApplication.java
  config/   AgentProperties, ModelConfig(ChatClient/Embedding/Qdrant/Redis beans)
  api/      ChatController, AdminKbController, PreferenceController, UserIdFilter, GlobalExceptionHandler
  trace/    Trace, TraceStep, TraceHolder(ThreadLocal 仅用于日志 MDC, 不用于工具), Tracer
  tool/     ToolResult, ToolException, ToolKeys, GuardedToolCallback, ToolBox,
            ProductTools, OrderTools, LogisticsTools, LogisticsClient(+MockLogisticsController), KnowledgeTools, SkillTools
  data/     ProductRepo, OrderRepo, PreferenceRepo, KbDocumentRepo, SummaryRepo (JdbcClient)
  rag/      ParsedDoc, Chunk, DocParser, DocChunker, SparseEncoder, SparseVec, KbStore, Hit, Rrf,
            QueryRewriter, Reranker, RetrievalService, RetrievalResult, IngestionService, KbSyncRunner
  skill/    SkillRegistry, Skill
  memory/   ChatMessage, ShortTermMemory, HistoryCompactor, MemoryService, MemoryContext, PreferenceExtractor
  agent/    Intent, RouteDecision, RouterAgent, Expert, ExpertFactory, AgentOrchestrator
  eval/     EvalRunner, EvalMetrics, LlmJudge, ChatCache, ReportWriter, datasets 记录类型
src/test/java/...  与上面同包结构
src/test/resources/eval/{retrieval,routing,tools,e2e}.jsonl
tools/GenerateKbDocs.java                        # 用 POI/PDFBox 生成 docx/pdf, 在项目外 main 方法运行
```

---

### Task 1: 工程脚手架 + 外部依赖验证

**Files:**
- Create: `pom.xml`, `docker-compose.yml`, `application.yml`, `schema.sql`, `MallAgentApplication.java`, `config/AgentProperties.java`, `config/ModelConfig.java`
- Test: `src/test/java/com/mall/agent/SpikeLiveTest.java`

**Interfaces:**
- Produces: `AgentProperties`(`@ConfigurationProperties("agent")`,嵌套 record:`mode`(`multi|single`)、`tool.maxFailures`、`memory.windowMessages/summarizeThreshold/keepRecent`、`rag.denseTopN/sparseTopN/rerankTopK/refuseThreshold/rewriteEnabled/sparseEnabled/rerankEnabled/hybridEnabled`);`ModelConfig` 暴露 `ChatClient.Builder`、`EmbeddingModel`、`QdrantClient`、`StringRedisTemplate`。
- yml 默认值:`agent.mode=multi, tool.max-failures=3, memory.window-messages=20, summarize-threshold=16, keep-recent=6, rag.dense-top-n=20, sparse-top-n=20, rerank-top-k=4, refuse-threshold=0.3`,rag 各开关默认 true;chat 模型 `qwen-plus`,router 模型 `qwen-turbo`(单独配置键 `agent.router-model`),embedding `text-embedding-v3` 维度 1024,base-url `https://dashscope.aliyuncs.com/compatible-mode`。

- [ ] **Step 1: Write `SpikeLiveTest`**,三个测试(均受 key 守卫):`chatReturnsText`(问"你好"得到非空)、`embeddingBatchOf12Works`(12 条文本,返回 12 个长度 1024 向量;验证批量上限,若失败则记录实际上限并让 Task 7 的摄入按该上限分批)、`toolCallingWorksWithQwen`(一个返回固定字符串的 `@Tool`,断言回答包含该字符串)。再加一个 Qdrant 测试 `hybridQuerySmoke`:建临时 collection(命名向量 `dense`(1024, Cosine) + 稀疏向量 `sparse`),写 2 点,分别用 dense、sparse 查询各返回结果;测完删除 collection(需本地 Qdrant 在 6334)。
- [ ] **Step 2: 写 pom/yml/docker-compose/schema.sql 与配置类**,`docker-compose.yml` 起 mysql:8、redis:7、qdrant/qdrant(暴露 3306/6379/6333/6334)。
- [ ] **Step 3: Run** `docker compose up -d` 后 `mvn -q test -Dtest=SpikeLiveTest`;Expected: 全部 PASS。任一失败 → 把实际现象记入 README"已知差异"并调整后续任务假设,**不得继续猜**。
- [ ] **Step 4: Commit** `chore: scaffold project and verify qwen/qdrant integration`

### Task 2: 数据访问层 + 模拟数据

**Files:**
- Create: `data/ProductRepo.java`, `data/OrderRepo.java`, `data/PreferenceRepo.java`, `data/KbDocumentRepo.java`, `data/SummaryRepo.java`, `src/main/resources/data.sql`
- Test: `data/RepoTest.java`(`@JdbcTest` + H2 MySQL 模式 + schema.sql)

**Interfaces:**
- Produces(record 与方法,Tasks 4/7/10 依赖):
  - `record Product(long id, String name, String category, String brand, BigDecimal price, int stock, String description, String attrs)`;`ProductRepo.search(String keyword, String category, BigDecimal min, BigDecimal max, String brand, int limit): List<Product>`;`findById(long): Optional<Product>`
  - `record OrderView(String orderNo, String status, BigDecimal totalAmount, String receiver, String address, LocalDateTime createdAt, LocalDateTime shippedAt, List<OrderLine> items)`;`record OrderLine(String productName, BigDecimal price, int quantity)`;`OrderRepo.findByNoAndUser(String orderNo, String userId): Optional<OrderView>`;`listByUser(String userId, String status /*nullable*/, int limit): List<OrderView>`
  - `record Preference(String key, String value, double confidence)`;`PreferenceRepo.upsert(String userId, Preference)`、`list(String userId)`、`deleteAll(String userId)`(upsert 以 `(user_id,pref_key,pref_value)` 唯一键,重复为更新置信度)
  - `record KbDoc(String docId, int version, String title, String category, String fileName, String contentHash, int chunkCount, boolean effective)`;`KbDocumentRepo.find(docId, version): Optional<KbDoc>`、`save(KbDoc)`(按唯一键 upsert)、`listByDocId(docId)`、`setEffective(docId, version, boolean)`、`delete(docId)`
  - `SummaryRepo.get(sessionId): Optional<String>`、`save(sessionId, userId, summary, coveredCount)`
- `data.sql`:≥30 件商品(覆盖手机/服饰/家电等,含不同品牌价位),用户 `u1001`、`u1002` 各 ≥3 个不同状态订单。

- [ ] **Step 1: Write failing tests**:`orderOfOtherUserIsInvisible`(`findByNoAndUser("<u1001的单>", "u1002")` 为空)、`searchFiltersByPriceAndBrand`、`preferenceUpsertIsIdempotent`(同键值写两次 list 仅 1 条且置信度为最新)、`kbDocSaveUpserts`。
- [ ] **Step 2: Run** `mvn -q test -Dtest=RepoTest`;Expected: FAIL(类不存在)。
- [ ] **Step 3: Implement repos** 用 `JdbcClient`;`OrderRepo` 查询 SQL 必须同时带 `order_no` 与 `user_id` 条件。
- [ ] **Step 4: Run**;Expected: PASS。
- [ ] **Step 5: Commit** `feat: data access layer and mock data`

### Task 3: 工具容错骨架(Guarded 工具)

**Files:**
- Create: `tool/ToolResult.java`, `tool/ToolException.java`, `tool/ToolKeys.java`, `tool/GuardedToolCallback.java`, `tool/ToolBox.java`
- Test: `tool/GuardedToolCallbackTest.java`

**Interfaces:**
- Produces:
  - `record ToolResult(boolean ok, Object data, String errorCode, String message, boolean retryable, String hint)`,静态 `ok(Object)`、`fail(String code, String message, boolean retryable, String hint)`,`toJson()`。
  - `ToolException(String code, String message, boolean retryable, String hint)`(unchecked)。
  - `ToolKeys.USER_ID = "userId"`。
  - `GuardedToolCallback implements ToolCallback`,构造 `(ToolCallback delegate, AtomicInteger failureCounter, int maxFailures, Tracer tracer)`:调用 delegate;捕获 `ToolException` → `fail(...)` JSON;捕获其他异常 → `fail("INTERNAL", ..., retryable=false)`;计数失败,计数达到 `maxFailures` 后**不再执行 delegate**,直接返回 `fail("TOO_MANY_FAILURES", ..., false, "请停止重试,向用户说明并请求澄清")`;每次调用经 `tracer` 记录工具名、脱敏参数、耗时、成败。
  - `ToolBox.build(List<Object> toolBeans, Tracer tracer): ToolCallback[]`:用 `ToolCallbacks.from(beans)` 取回调,逐个包 `GuardedToolCallback`,**同一次 build 共享一个计数器**(即每请求一个预算)。
- Consumes: Task 9 `Tracer`(本任务先定义最小接口 `Tracer.tool(String name, String argsJson, long millis, boolean ok)` 并提供空实现,Task 11 替换为真实实现)。

- [ ] **Step 1: Write failing tests**:`toolExceptionBecomesStructuredError`(delegate 抛 `ToolException("INVALID_ARG","订单号格式不对",true,"订单号为 16 位数字")` → 返回 JSON 含 `"ok":false` 与 `"hint"`)、`unexpectedExceptionBecomesInternalError`、`stopsExecutingAfterMaxFailures`(maxFailures=2,delegate 总失败;第 3 次调用 delegate 未被调用且 errorCode=`TOO_MANY_FAILURES`)、`successDoesNotCountAsFailure`、`budgetSharedAcrossToolsInOneBuild`。
- [ ] **Step 2: Run** `mvn -q test -Dtest=GuardedToolCallbackTest`;Expected: FAIL。
- [ ] **Step 3: Implement** 如接口所述。
- [ ] **Step 4: Run**;Expected: PASS。
- [ ] **Step 5: Commit** `feat: guarded tool callbacks with structured errors and retry budget`

### Task 4: 业务工具(商品、订单、物流 + 熔断)

**Files:**
- Create: `tool/ProductTools.java`, `tool/OrderTools.java`, `tool/LogisticsTools.java`, `tool/LogisticsClient.java`, `tool/MockLogisticsController.java`
- Test: `tool/OrderToolsTest.java`, `tool/ProductToolsTest.java`, `tool/LogisticsToolsTest.java`

**Interfaces:**
- Consumes: Task 2 repos;Task 3 `ToolResult/ToolException/ToolKeys`。
- Produces(`@Tool` 方法,**返回 `String`(ToolResult JSON)**;最后一个参数 `ToolContext ctx`,userId 用 `(String) ctx.getContext().get(ToolKeys.USER_ID)`):
  - `ProductTools.searchProducts(String keyword, String category, Double minPrice, Double maxPrice, String brand, ToolContext)`;`getProductDetail(long productId, ToolContext)`
  - `OrderTools.getOrder(String orderNo, ToolContext)`;`listMyOrders(String status, ToolContext)`(status 可空,枚举见 DDL 注释)
  - `LogisticsTools.getLogistics(String orderNo, ToolContext)`:先用 `OrderRepo.findByNoAndUser` 校验归属,再调 `LogisticsClient.query(orderNo): LogisticsInfo`
  - `LogisticsClient`:`RestClient` 调 `agent.logistics.base-url`(默认指向本应用的 `/mock/logistics/{orderNo}`),连接+读取超时 `agent.logistics.timeout-ms`(默认 1500),带 Resilience4j `@TimeLimiter`/`@Retry`(仅幂等读,最多 2 次)/`@CircuitBreaker`(名称 `logistics`);降级方法返回 `LogisticsInfo.unavailable()`。`MockLogisticsController` 按 `agent.logistics.mock-fail-rate`(默认 0.3)随机延迟 3s 或返回 500。
- 校验规则:订单号必须匹配 `^\d{16}$`,否则 `ToolException("INVALID_ARG", ..., retryable=true, hint="订单号为16位数字,请向用户确认")`;`status` 非法值同理;订单不存在**或不属于当前用户**统一 `ToolException("NOT_FOUND","未找到该订单",false,null)`。

- [ ] **Step 1: Write failing tests**:`getOrderRejectsMalformedNo`(返回 errorCode=INVALID_ARG 且 retryable=true)、`getOrderOfAnotherUserLooksLikeNotFound`(用 u1002 查 u1001 的单,JSON 与"不存在的合法订单号"的 JSON 完全一致)、`listMyOrdersRejectsUnknownStatus`、`searchProductsMapsFilters`(Mock repo,断言传参)、`logisticsTimeoutDegrades`(LogisticsClient 抛超时 → 结果 ok=true 且 `data.available=false`,文案含"暂时无法获取",不含轨迹节点)、`logisticsChecksOwnershipBeforeCalling`(他人订单时 LogisticsClient 零调用)、`toolMethodsDoNotExposeUserIdParameter`(反射检查所有 `@Tool` 方法参数名与 schema 均无 `userId`)。熔断:`circuitOpensAfterRepeatedFailures`(Resilience4j 配置 sliding-window 5、failure-rate 50%,用 Mock 连续失败 5 次后第 6 次不再发起真实调用)。
- [ ] **Step 2: Run** `mvn -q test -Dtest='OrderToolsTest,ProductToolsTest,LogisticsToolsTest'`;Expected: FAIL。
- [ ] **Step 3: Implement** 工具与客户端;`@Tool` 的 description 写清"何时用/何时不用",参数用 `@ToolParam(description=...)` 标注枚举和范围;返回结果只含必要字段。
- [ ] **Step 4: Run**;Expected: PASS。
- [ ] **Step 5: Commit** `feat: product/order/logistics tools with validation and circuit breaker`

### Task 5: 文档解析与切分

**Files:**
- Create: `rag/ParsedDoc.java`, `rag/Chunk.java`, `rag/DocParser.java`, `rag/DocChunker.java`
- Test: `rag/DocParserTest.java`, `rag/DocChunkerTest.java`;测试夹具 `src/test/resources/rag/{sample.md,sample.docx}`(夹具用 POI 在测试内生成)

**Interfaces:**
- Produces:
  - `record ParsedDoc(String docId, int version, String title, String category, List<Section> sections)`;`record Section(List<String> titlePath, String text, boolean isTable, boolean isFaq)`
  - `DocParser.parse(Path file, String category): ParsedDoc`:从文件名解析 `docId/version`;`.md` 按 `#` 层级切 Section,`## Q:`/`Q:` 开头的问答对标记 `isFaq`;`.docx` 用 POI 遍历段落(Heading 样式得 titlePath)与表格(转 Markdown 表格文本,`isTable=true`);`.pdf` 用 Tika 尽力提取文本(按空行分段,无表格保证)。
  - `record Chunk(String id, String docId, int version, String category, String titlePath, String text, String parentText, boolean effective, String contentHash)`
  - `DocChunker.chunk(ParsedDoc, String contentHash, boolean effective): List<Chunk>`:FAQ 每条一块(parentText=text);表格整体一块;普通 Section 若 > `childSize`(默认 300 字)按句切成子块,`parentText` 为整个 Section;`id` 为确定性 UUID(`docId|version|序号`),保证重复摄入幂等。
- Consumes: 无。

- [ ] **Step 1: Write failing tests**:`markdownSplitsByHeadingWithTitlePath`(断言 titlePath 形如 `售后政策 > 七日无理由 > 例外情况`)、`faqPairsBecomeSingleChunks`、`docxTableBecomesMarkdownTableChunk`(断言 chunk 文本含 `|` 与表头单元格,且整表只有 1 块)、`longSectionSplitsIntoChildrenSharingParent`(所有子块 `parentText` 相同且长度 ≥ 子块)、`chunkIdsAreDeterministic`(同输入两次 ID 序列相同)、`fileNameParsesDocIdAndVersion`(`return-policy__v2.md` → `return-policy`, 2)。
- [ ] **Step 2: Run** `mvn -q test -Dtest='DocParserTest,DocChunkerTest'`;Expected: FAIL。
- [ ] **Step 3: Implement**。
- [ ] **Step 4: Run**;Expected: PASS。
- [ ] **Step 5: Commit** `feat: structure-aware document parsing and chunking`

### Task 6: 稀疏编码 + RRF + Qdrant 存储

**Files:**
- Create: `rag/SparseVec.java`, `rag/SparseEncoder.java`, `rag/Hit.java`, `rag/Rrf.java`, `rag/KbStore.java`
- Test: `rag/SparseEncoderTest.java`, `rag/RrfTest.java`, `rag/KbStoreIT.java`(需 Qdrant,守卫同 Task 1,使用独立临时 collection)

**Interfaces:**
- Produces:
  - `record SparseVec(int[] indices, float[] values)`;`SparseEncoder.encode(String text): SparseVec`:中文取相邻字二元组(单个汉字孤立时取单字),英文数字按整词小写;`indices = abs(hash(token)) % 2^20`;`values` = 词频的 `1+log(tf)`;同索引合并;结果按索引升序。
  - `record Hit(String chunkId, String docId, int version, String titlePath, String text, String parentText, double score)`
  - `Rrf.fuse(List<List<Hit>> rankedLists, int k /*60*/, int limit): List<Hit>`:按 `Σ 1/(k+rank)` 排序,同 `chunkId` 合并,score 为融合分。
  - `KbStore`(`@Component`,collection 名 `agent.rag.collection`,默认 `mall_kb`):`ensureCollection()`(命名向量 `dense` 1024 Cosine + 稀疏向量 `sparse`)、`upsert(List<Chunk> chunks, List<float[]> dense)`、`deleteByDocId(String docId)`、`setEffective(String docId, int version, boolean effective)`(用 payload 更新)、`denseSearch(float[] q, Filter f, int topN): List<Hit>`、`sparseSearch(SparseVec q, Filter f, int topN): List<Hit>`;`record Filter(boolean onlyEffective, String category /*nullable*/)`。payload 保存 Chunk 全部字段。
- Consumes: Task 5 `Chunk`。

- [ ] **Step 1: Write failing tests**:`chineseBigramsMatchAcrossPhrases`("七日无理由退货" 与 "无理由退货" 的稀疏向量索引交集非空)、`sparseEncodingIsDeterministicAndSorted`、`rrfPromotesItemsRankedWellInBothLists`(A 在两表分别第 2、第 2,B 仅在一表第 1 → A 排名高于 B 需按公式计算后断言具体顺序)、`rrfMergesDuplicatesByChunkId`、`rrfRespectsLimit`。IT:`upsertThenDenseAndSparseSearch`、`effectiveFilterExcludesOldVersion`(把 v1 `setEffective(false)` 后两路检索都不返回 v1)、`deleteByDocIdRemovesAllVersions`。
- [ ] **Step 2: Run** 单测;Expected: FAIL。
- [ ] **Step 3: Implement**;Qdrant 过滤用 payload `effective` 与 `category`。
- [ ] **Step 4: Run** `mvn -q test -Dtest='SparseEncoderTest,RrfTest'` 与 `mvn -q verify -Dit.test=KbStoreIT`;Expected: PASS。
- [ ] **Step 5: Commit** `feat: sparse encoder, RRF fusion and qdrant store`

### Task 7: 摄入、增量更新、版本管理、管理接口、模拟 KB

**Files:**
- Create: `rag/IngestionService.java`, `rag/KbSyncRunner.java`, `api/AdminKbController.java`, `api/AdminTokenFilter.java`, `tools/GenerateKbDocs.java`, `src/main/resources/kb/**`
- Test: `rag/IngestionServiceTest.java`, `api/AdminKbControllerTest.java`(`@WebMvcTest`)

**Interfaces:**
- Consumes: Task 2 `KbDocumentRepo`, Task 5 `DocParser/DocChunker`, Task 6 `KbStore`, `EmbeddingModel`(分批,批大小取 Task 1 验证结果,默认 10)。
- Produces:
  - `IngestionService.ingest(Path file, String category): IngestResult`(`enum Status{INGESTED, SKIPPED_UNCHANGED}`,`record IngestResult(String docId,int version,Status status,int chunks)`):算文件 SHA-256;若 `(docId,version)` 已存在且 hash 相同 → `SKIPPED_UNCHANGED`;否则删除该 `(docId,version)` 旧块、重新解析/切分/向量化/写入、`KbDocumentRepo.save`;随后重算该 docId 的 effective:版本最高者 true,其余 false(同步到 Qdrant `setEffective` 与表)。
  - `IngestionService.reindexAll(Path kbDir)`(忽略 hash 强制重建)、`syncAll(Path kbDir)`(增量)、`remove(String docId)`。
  - `KbSyncRunner`(`ApplicationRunner`)启动时 `syncAll(classpath kb 目录)`,可由 `agent.rag.sync-on-startup` 关闭。
  - HTTP:`POST /admin/kb/upload`(multipart `file`, 参数 `category`,文件落盘到 `agent.rag.upload-dir` 后 ingest)、`DELETE /admin/kb/{docId}`、`POST /admin/kb/reindex`;`AdminTokenFilter` 校验 `X-Admin-Token`,不符 401。
- 模拟 KB(用 `tools/GenerateKbDocs.java` 生成 docx/pdf 并提交产物;md 手写),至少包含:`售后/return-policy__v1.md` 与 `__v2.docx`(v1 写 7 天无理由、v2 改为含"定制/贴身类除外"例外并调整退款时效)、`物流/shipping-fee__v1.docx`(运费模板表格)、`会员营销/coupon-rules__v1.md`(叠加规则)、`选购指南/size-guide__v1.docx`(尺码对照表)、`FAQ/faq__v1.md`(≥15 问答,含"能退吗"类口语问法)、`支付账户/payment__v1.md`、`活动规则/promo-2026__v1.pdf`。

- [ ] **Step 1: Write failing tests**:`unchangedFileIsSkipped`(第二次 ingest 同文件,Embedding/Qdrant 零调用,Status=SKIPPED_UNCHANGED)、`newVersionMarksOldIneffective`(ingest v1 再 ingest v2:v1 `effective=false` 被同步到 Qdrant 与表,v2 为 true)、`changedContentReplacesOldChunks`(同版本内容变化 → 先 `deleteByDocId`/按版本删除再写入)、`reindexIgnoresHash`、`removeDeletesStoreAndTable`;Controller:`uploadWithoutTokenIs401`、`uploadWithTokenIngests`、`deleteReturnsOk`。
- [ ] **Step 2: Run** `mvn -q test -Dtest='IngestionServiceTest,AdminKbControllerTest'`;Expected: FAIL。
- [ ] **Step 3: Implement**,并编写/运行 `GenerateKbDocs` 生成文档、核对 `kb/` 结构符合 Global Constraints。
- [ ] **Step 4: Run** 同上;Expected: PASS。再启动应用+Qdrant 手工确认启动同步后 `kb_document` 有记录、再次启动全部 `SKIPPED_UNCHANGED`。
- [ ] **Step 5: Commit** `feat: ingestion with incremental update, versioning and admin API`

### Task 8: 检索链路(改写、混合、过滤、重排、拒答)

**Files:**
- Create: `rag/QueryRewriter.java`, `rag/Reranker.java`, `rag/RetrievalResult.java`, `rag/RetrievalService.java`
- Test: `rag/QueryRewriterTest.java`, `rag/RerankerTest.java`, `rag/RetrievalServiceTest.java`

**Interfaces:**
- Consumes: Task 6 `KbStore/Rrf/SparseEncoder/Hit/Filter`,`EmbeddingModel`,`AgentProperties.rag`,`ChatClient`。
- Produces:
  - `QueryRewriter.rewrite(String query, List<ChatMessage> history): List<String>`:一次 LLM 调用,结构化输出 `{standalone: String, variants: List<String>}`(指代消解 + 口语转书面 + 2~3 个扩展),返回 `[standalone, ...variants]`(去重、最多 4 条);LLM 失败时降级返回 `[query]`。
  - `Reranker.rerank(String query, List<Hit> candidates, int topK): List<Hit>`:`RestClient` 调 DashScope `gte-rerank` REST(`agent.rag.rerank-model`),用返回的 `relevance_score` 重写 `Hit.score` 并取 topK;调用失败 → 降级为保持 RRF 顺序截断,并在 `RetrievalResult.rerankSkipped=true`。
  - `record RetrievalResult(List<Hit> hits, boolean refused, boolean rerankSkipped, List<String> queries)`
  - `RetrievalService.retrieve(String query, List<ChatMessage> history, String category): RetrievalResult`:按开关 `rewriteEnabled`(关则 queries=[query])→ 对每个 query 做 dense 查询,`sparseEnabled` 时再做 sparse 查询(`hybridEnabled=false` 等价于只 dense)→ `Rrf.fuse` → `rerankEnabled` 时 rerank,否则按 RRF 截断 topK → 若最高分 < `refuseThreshold`(仅 rerank 开启时判定;未开启 rerank 时以 RRF 结果是否为空判定)则 `refused=true, hits=[]`;命中以 `parentText` 展开并按 `parentText` 去重;过滤 `Filter(true, category)`。
- 用 `Trace` 记录各步(Task 11 提供 `Tracer`,此处用同 Task 3 的最小接口扩展 `step(String name, String detail)`)。

- [ ] **Step 1: Write failing tests**(依赖全部 Mock):`rewriterFallsBackToOriginalOnLlmError`、`rewriterDedupesAndCapsAtFour`、`rerankerReordersByRelevanceScore`、`rerankerFailureKeepsRrfOrder`(`rerankSkipped=true`)、`retrieveRunsDenseAndSparseForEachQuery`(2 个 query + 稀疏开 → Store 各被调用 2 次)、`sparseDisabledSkipsSparseSearch`、`rewriteDisabledUsesOriginalQueryOnly`、`lowRerankScoreRefuses`(最高分 0.1 < 0.3 → `refused=true`)、`emptyKnowledgeBaseRefuses`(Store 返回空 → refused)、`hitsExpandToParentTextAndDedupe`、`categoryFilterPassedToStore`。
- [ ] **Step 2: Run** `mvn -q test -Dtest='QueryRewriterTest,RerankerTest,RetrievalServiceTest'`;Expected: FAIL。
- [ ] **Step 3: Implement**。
- [ ] **Step 4: Run**;Expected: PASS。
- [ ] **Step 5: Commit** `feat: retrieval pipeline with rewrite, hybrid recall, rerank and refusal`

### Task 9: Skill 与知识/Skill 工具

**Files:**
- Create: `skill/Skill.java`, `skill/SkillRegistry.java`, `tool/SkillTools.java`, `tool/KnowledgeTools.java`, `src/main/resources/skills/{return-and-refund,order-troubleshooting,product-recommendation}/SKILL.md`
- Test: `skill/SkillRegistryTest.java`, `tool/KnowledgeToolsTest.java`

**Interfaces:**
- Produces:
  - `record Skill(String name, String description, String body)`;`SkillRegistry`(启动时扫描 `classpath:skills/*/SKILL.md`,解析 frontmatter `name/description`,缺字段启动失败):`catalog(Set<String> allowed): String`(每行 `- name: description`)、`load(String name, Set<String> allowed): Optional<Skill>`。
  - `SkillTools.loadSkill(String name, ToolContext)`:构造时带 `Set<String> allowed`(每个专家一个实例,见 Task 12);不在允许集合 → `ToolException("NOT_FOUND","没有这个 Skill",true,"可用 Skill:"+列表)`;返回 body。
  - `KnowledgeTools.searchKnowledge(String query, ToolContext)`:调 `RetrievalService.retrieve(query, history, category)`(history/category 由构造时注入的 `Supplier` 提供,Task 12 按请求构造实例);`refused` 时返回 `ok` 且 `data={"answerable":false,"message":"知识库中没有相关信息"}`;否则 `data={"answerable":true,"passages":[{"source":"文档名 > 标题路径","text":...}]}`。
- 三个 SKILL.md 各含:适用场景、步骤、话术、注意事项(例如退换货需先确认订单状态与是否在期限内,依据以知识库检索结果为准)。

- [ ] **Step 1: Write failing tests**:`catalogListsOnlyAllowedSkills`、`loadReturnsBodyForAllowed`、`loadDeniedForOtherExpertsSkill`、`missingFrontmatterFailsStartup`、`searchKnowledgeReturnsRefusalPayloadWhenRefused`、`searchKnowledgePassagesCarrySource`。
- [ ] **Step 2: Run** `mvn -q test -Dtest='SkillRegistryTest,KnowledgeToolsTest'`;Expected: FAIL。
- [ ] **Step 3: Implement**。
- [ ] **Step 4: Run**;Expected: PASS。
- [ ] **Step 5: Commit** `feat: skills with progressive disclosure and knowledge tool`

### Task 10: 记忆(短期、摘要、长期偏好)

**Files:**
- Create: `memory/ChatMessage.java`, `memory/ShortTermMemory.java`, `memory/HistoryCompactor.java`, `memory/MemoryContext.java`, `memory/MemoryService.java`, `memory/PreferenceExtractor.java`, `api/PreferenceController.java`
- Test: `memory/ShortTermMemoryTest.java`(Mock Redis 模板),`memory/HistoryCompactorTest.java`, `memory/MemoryServiceTest.java`, `memory/PreferenceExtractorTest.java`, `api/PreferenceControllerTest.java`

**Interfaces:**
- Produces:
  - `record ChatMessage(String role /*user|assistant*/, String content)`
  - `ShortTermMemory.append(String sessionId, List<ChatMessage>)`、`load(String sessionId): List<ChatMessage>`、`replace(String sessionId, List<ChatMessage>)`;Redis List,TTL `agent.memory.ttl-minutes`(默认 120)。
  - `HistoryCompactor.compact(String oldSummary, List<ChatMessage> messages): Compacted`(`record Compacted(String summary, List<ChatMessage> recent)`):当 `messages.size() > summarizeThreshold` 时,把除最近 `keepRecent` 条之外的部分与旧摘要一起交给 LLM 生成新摘要(提示词要求**必须保留订单号、商品名、用户已作出的决定与未解决问题**);未超阈值原样返回(summary=oldSummary)。LLM 失败 → 保留旧摘要并按 `windowMessages` 硬截断,不抛出。
  - `record MemoryContext(String summary, List<ChatMessage> recent, List<Preference> preferences)`
  - `MemoryService.load(String userId, String sessionId): MemoryContext`(Redis 的消息 + `SummaryRepo` 的摘要 + `PreferenceRepo.list`);`save(String userId, String sessionId, String userMsg, String assistantMsg)`:追加消息 → 调 `HistoryCompactor` → 若摘要变化则 `SummaryRepo.save` 并 `replace` Redis 中消息为 recent → **异步**(`@Async`,独立线程池)调 `PreferenceExtractor.extractAndSave(userId, userMsg, assistantMsg)`。
  - `PreferenceExtractor.extractAndSave(...)`:LLM 结构化输出 `List<{key,value,confidence}>`,仅接受 key ∈ {brand,budget,size,style,category};置信度 < 0.5 丢弃;`PreferenceRepo.upsert`;失败仅记日志。
  - `GET /memory/preferences`、`DELETE /memory/preferences`(按 `X-User-Id`)。
- 偏好渲染:`MemoryContext.preferencesText()` 输出"用户偏好:品牌=华为;预算=2000-3000"一行文本(空时返回空串)。

- [ ] **Step 1: Write failing tests**:`appendAndLoadKeepsOrder`、`ttlIsSetOnAppend`、`compactorLeavesShortHistoryUntouched`、`compactorKeepsRecentAndSummarizesOlder`(阈值 16、keepRecent 6、传入 20 条 → recent 恰为末 6 条,LLM 收到的提示含旧摘要与前 14 条)、`compactorPromptDemandsKeyFacts`(提示词包含"订单号")、`compactorFallsBackToTruncationOnLlmFailure`、`saveTriggersAsyncPreferenceExtraction`、`extractorDropsLowConfidenceAndUnknownKeys`、`extractorUpsertsAccepted`、`preferencesTextFormat`、Controller:`getReturnsOnlyCallersPreferences`、`deleteClearsCallersPreferences`。
- [ ] **Step 2: Run** `mvn -q test -Dtest='ShortTermMemoryTest,HistoryCompactorTest,MemoryServiceTest,PreferenceExtractorTest,PreferenceControllerTest'`;Expected: FAIL。
- [ ] **Step 3: Implement**。
- [ ] **Step 4: Run**;Expected: PASS。
- [ ] **Step 5: Commit** `feat: three-layer memory with summarization and preference extraction`

### Task 11: Trace 与指标

**Files:**
- Create: `trace/Trace.java`, `trace/TraceStep.java`, `trace/Tracer.java`(替换 Task 3/8 的最小接口为真实实现), `trace/TraceHolder.java`
- Modify: `tool/GuardedToolCallback.java`(仅依赖接口,无需改动签名)
- Test: `trace/TracerTest.java`

**Interfaces:**
- Produces:
  - `Tracer` 方法(Task 3/8 已使用):`tool(String name, String argsJson, long millis, boolean ok)`、`step(String name, String detail)`;新增 `llm(String stage, String model, long millis, Usage usage)`(`record Usage(int promptTokens, int completionTokens)`;拿不到 usage 时由调用方用字符数估算并令 `estimated=true`)、`traceId()`、`finish(): Trace`。
  - `Trace(String traceId, List<TraceStep> steps, long totalMillis, int totalTokens)`;`TraceStep(String type, String name, String detail, long millis, boolean ok)`。
  - 一个请求一个 `Tracer` 实例(显式传递,**不放 ThreadLocal 给工具用**);`TraceHolder` 只负责把 traceId 放入 MDC 以便日志串联。
  - Micrometer:计时器 `agent.stage.duration{stage}`、计数器 `agent.tool.calls{tool,ok}`、`agent.tokens{type}`、`agent.rag.refusals`;`finish()` 时输出一行结构化日志(`traceId=… route=… tools=… tokens=… ms=…`),参数脱敏(订单号保留后 4 位,手机号掩码)。
- Actuator 暴露 `health,metrics,prometheus`。

- [ ] **Step 1: Write failing tests**:`recordsStepsInOrderWithTraceId`、`toolArgsAreMasked`(`orderNo=1234567890123456` → 日志/记录中仅 `************3456`)、`tokenTotalSumsLlmSteps`、`estimatedUsageIsFlagged`、`metricsIncrementOnToolFailure`(SimpleMeterRegistry 断言 `agent.tool.calls{ok=false}` 为 1)。
- [ ] **Step 2: Run** `mvn -q test -Dtest=TracerTest`;Expected: FAIL。
- [ ] **Step 3: Implement**。
- [ ] **Step 4: Run**;Expected: PASS。
- [ ] **Step 5: Commit** `feat: per-request trace and metrics`

### Task 12: Agent 编排 + Chat API + 前端

**Files:**
- Create: `agent/Intent.java`, `agent/RouteDecision.java`, `agent/RouterAgent.java`, `agent/Expert.java`, `agent/ExpertFactory.java`, `agent/AgentOrchestrator.java`, `api/ChatController.java`, `api/UserIdFilter.java`, `api/GlobalExceptionHandler.java`, `src/main/resources/static/index.html`
- Test: `agent/RouterAgentTest.java`, `agent/ExpertFactoryTest.java`, `agent/AgentOrchestratorTest.java`, `api/ChatControllerTest.java`

**Interfaces:**
- Consumes: Task 3 `ToolBox`、Task 4/9 工具、Task 10 `MemoryService`、Task 11 `Tracer`、Task 8 `RetrievalService`。
- Produces:
  - `enum Intent {RECOMMEND, ORDER, KNOWLEDGE, CHITCHAT}`;`record RouteDecision(Intent intent, String category /*nullable, 知识库类别*/, String reply /*仅 CHITCHAT*/)`
  - `RouterAgent.route(MemoryContext ctx, String message, Tracer t): RouteDecision`:用 `agent.router-model` 与结构化输出;只传摘要+最近 4 条消息;失败或解析错误 → 降级为 `KNOWLEDGE`(并记 trace)。多意图消息只取主意图(提示词里写明)。
  - `Expert`(`record Expert(String name, String systemPrompt, List<Object> toolBeans, Set<String> skills)`);`ExpertFactory.forIntent(Intent, MemoryContext, String category, Tracer): Expert`:RECOMMEND→`ProductTools`+`SkillTools(["product-recommendation"])`,系统提示注入 `preferencesText()`;ORDER→`OrderTools,LogisticsTools`+`SkillTools(["return-and-refund","order-troubleshooting"])`;KNOWLEDGE→`KnowledgeTools(history/category)`+`SkillTools(["return-and-refund"])`;`agent.mode=single` 时 `forIntent` 总返回合并全部工具与 Skill 的单一 Expert(不经 Router,Orchestrator 跳过路由)。所有系统提示须含:回答基于工具/知识库结果、引用来源、工具失败时如实说明、不编造。
  - `AgentOrchestrator.stream(String userId, String sessionId, String message): Flux<String>`:`memory.load` → `route`(CHITCHAT 直接返回 `reply`)→ 构造 Expert → `ChatClient...toolCallbacks(toolBox.build(...)).toolContext(Map.of(ToolKeys.USER_ID,userId)).stream()` → 流结束后 `memory.save(...)` 与 `tracer.finish()`。
  - `POST /chat`(JSON `{sessionId, message}`,`Accept: text/event-stream`,SSE 逐段输出;首个事件之前不产生副作用);`UserIdFilter` 对 `/chat`、`/memory/**` 校验 `X-User-Id` 非空否则 401;校验:`message` 非空且 ≤ 2000 字符、`sessionId` 非空 ≤ 64 字符,否则 400(`GlobalExceptionHandler` 统一 JSON 错误体 `{code,message}`)。
  - `static/index.html`:单页,含 userId 与 sessionId 输入(sessionId 默认随机)、消息输入框、`fetch` 读取 SSE 流式渲染,显示 traceId。

- [ ] **Step 1: Write failing tests**:`routerFallsBackToKnowledgeOnError`、`routerReceivesOnlyRecentMessages`、`recommendExpertGetsPreferencesAndOnlyProductTools`(断言工具集合无 `getOrder`)、`orderExpertHasNoProductTools`、`singleModeMergesAllToolsAndSkipsRouter`、`chitchatReturnsRouterReplyWithoutExpert`、`orchestratorSavesMemoryAfterStreamCompletes`、`orchestratorPassesUserIdViaToolContext`;Controller:`missingUserIdIs401`、`blankMessageIs400`、`messageOver2000CharsIs400`(三者均断言 Orchestrator 零调用)、`missingSessionIdIs400`、`validRequestStreamsSse`。
- [ ] **Step 2: Run** `mvn -q test -Dtest='RouterAgentTest,ExpertFactoryTest,AgentOrchestratorTest,ChatControllerTest'`;Expected: FAIL。
- [ ] **Step 3: Implement**。
- [ ] **Step 4: Run**;Expected: PASS。再 `docker compose up -d` + `mvn spring-boot:run`,在浏览器用 `u1001` 手工验证:问"我的订单 xxx 到哪了"、"推荐 3000 元左右的手机"、"能退吗"(确认进入各专家、流式输出、回答带引用)。
- [ ] **Step 5: Commit** `feat: multi-agent orchestration, chat API and frontend`

### Task 13: 评测运行器

**Files:**
- Create: `eval/EvalRunner.java`, `eval/EvalMetrics.java`, `eval/LlmJudge.java`, `eval/ChatCache.java`, `eval/ReportWriter.java`, `eval/datasets/*.java`(记录类型),`src/test/resources/eval/{retrieval,routing,tools,e2e}.jsonl`
- Test: `eval/EvalMetricsTest.java`, `eval/ReportWriterTest.java`

**Interfaces:**
- Produces:
  - `EvalMetrics.recallAtK(List<String> expected, List<String> retrieved, int k): double`、`mrr(...)`、`accuracy(int correct, int total)`。
  - `LlmJudge.judge(String question, String answer, String reference, List<String> sources): JudgeScore`(`record JudgeScore(int correctness /*1-5*/, int faithfulness /*1-5*/, boolean hasCitation)`);rubric 写在提示词中:5=与参考一致且无编造,3=部分正确,1=错误或编造;temperature=0。
  - `ChatCache`:LLM 响应按 `hash(model+prompt)` 缓存到 `eval-cache/`,`agent.eval.use-cache=true` 时命中直接返回,保证重复评测可复现。
  - 数据集(jsonl)字段:retrieval `{query, expectedDocIds[], answerable}`;routing `{query, expectedIntent}`;tools `{query, setup, expectedTool, expectedArgs}`;e2e `{query, userId, reference}`。每个文件 ≥ 20 条,retrieval 必须包含 Task 7 所述四类难点各 ≥ 3 条(例外条款、新旧版本、表格、口语问法)与 ≥ 5 条库外问题(`answerable=false`)。
  - `EvalRunner`(`ApplicationRunner`,仅 `spring.profiles.active=eval`,数据集目录 `agent.eval.dataset-dir=src/test/resources/eval`,报告目录 `eval-reports/`):依次跑 **检索消融**(只稠密 / +稀疏 / +改写 / +重排,通过临时覆盖 `AgentProperties.rag` 开关)报告 Recall@4、MRR 与拒答正确率;**路由准确率**;**工具选择与参数正确率**(用 single 与 multi 两种模式各跑一遍,附平均 token 与耗时);**端到端**打分(LLM 裁判);输出 `eval-reports/<timestamp>.md` 和 `.json`,并与最近一份历史报告逐指标对比(Δ)。
- `ReportWriter.write(EvalReport, Optional<EvalReport> previous)` 渲染 Markdown 表格,含失败用例列表(query、期望、实际)。

- [ ] **Step 1: Write failing tests**:`recallAtKCountsExpectedFoundInTopK`、`mrrUsesFirstRelevantRank`、`mrrIsZeroWhenNothingRelevant`、`reportShowsDeltaAgainstPrevious`、`reportListsFailedCases`。
- [ ] **Step 2: Run** `mvn -q test -Dtest='EvalMetricsTest,ReportWriterTest'`;Expected: FAIL。
- [ ] **Step 3: Implement** 与编写数据集、Runner。
- [ ] **Step 4: Run** 单测 PASS;再运行 `mvn spring-boot:run -Dspring-boot.run.profiles=eval`,Expected: 在 `eval-reports/` 生成报告,消融表中四行指标均有数值。把一份真实报告拷到 `docs/eval-report-sample.md`。
- [ ] **Step 5: Commit** `feat: evaluation runner with ablations and reports`

### Task 14: README(面试讲法)

**Files:**
- Create: `README.md`

- [ ] **Step 1: 写 README**,必须含:架构图(沿用设计文档第 4 节);一次请求的完整链路走读(逐步对应类名);五大知识点各一节"怎么做 / 为什么 / 替代方案与取舍 / 面试可能追问"(RAG:解析切分、检索不准的四步优化与消融结果、更新维护;工具:设计原则、失败三类处理;Skill:渐进披露;多 Agent vs 单 Agent 的取舍与 `agent.mode` 对比结果;记忆三层;评测与可观测);启动步骤(docker compose、环境变量、建表、运行、访问页面)、评测命令、已知差异(Task 1 验证中发现的)。引用的评测数字必须来自 Task 13 的真实报告。
- [ ] **Step 2: 校验** 按 README 的启动步骤从零走一遍(新终端),Expected: 页面可聊天、评测可运行。
- [ ] **Step 3: Commit** `docs: README with architecture walkthrough and interview notes`

---

## Self-Review 记录

- **Spec coverage**:§3 技术栈→T1;§4 架构/对照开关→T12;§5.1–5.2→T5/T7;§5.3→T6/T8;§5.4→T7;§6.1–6.2→T3/T4;§6.3→T9;§7→T10;§8.1→T13;§8.2→T11;§9 风格→Global Constraints;§10 交付物→T1–T14;§12 各决定→已落入对应任务(Qdrant 直连 T6、稀疏 T6、Router T12、userId T3/T4/T12、KB 文件 T7、工具预算 T3、token 估算 T11、admin T7、记忆共享 T10/T12)。
- **类型一致性**:`ToolResult/ToolKeys`(T3)被 T4/T9/T12 使用;`Tracer.tool/step/llm`(T3 最小接口 → T11 实现)名称一致;`Hit/Filter/Chunk`(T5/T6)被 T7/T8 使用;`MemoryContext/ChatMessage`(T10)被 T8/T12 使用;`RetrievalService.retrieve` 签名 T8 定义、T9 使用一致。
- **注意**:Task 3/8 先于 Task 11,故 `Tracer` 在 T3 先以最小接口 + 空实现存在,T11 补全;执行者按此顺序。
