# cosmic-cost-ai 项目说明（供 Claude Code 使用）

基于 COSMIC 功能点方法的软件造价 AI 辅助系统。详细功能、接口、存储结构见 `README.md`。

## 技术栈与命令
- Java 8 + Spring Boot 2.7.18 + Maven，LangChain4j 调 DeepSeek（OpenAI 兼容接口）。不要升级 JDK/Spring 大版本。
- 编译：`mvn -q clean package -DskipTests`；测试：`mvn test`；单测：`mvn -Dtest=类名 test`
- 启动：`start.bat`（需要 OPENAI_API_KEY），默认端口 8080。
- 无数据库，项目数据在 `storage/`，导出在 `output/`。**不要删除或批量改写 `storage/`、`output/` 下的历史数据。**

## 代码结构
- `src/main/java/com/li/jc/cosmiccostai/`：controller / service / dto / model / config / common
- 核心服务：`SmartSplitPipelineService`（智能拆分流水线）、`AiExtractionService`（COSMIC 初拆）、`ReuseDegreeAiAdjustmentService`（复用度 AI 调整）、`RuleValidationService`（规则初审）、`ExcelReviewImportService`（已拆分 Excel 导入）、`SplitRuleConfigService`（规则文件加载/迁移）、`ReuseAuditSimilarity`（相似查重）
- 前端：`src/main/resources/static/index.html`（单文件）
- 拆分规则（提示词与初审共用的唯一规则源）：`src/main/resources/static/split-check-rules.md`；运行时副本 `storage/config/split-check-rules.md`，两份必须保持一致。

## 工作约定
- 中文注释与中文提交说明；改动前先读相关 service 和对应测试。
- 每项改动必须补/改 JUnit 测试，`mvn test` 全绿再提交。
- 复用 CFP 统一使用精确 1/3，不要再写死 0.33。
- 规则文件修改需同时提升规则版本号，并让 `SplitRuleConfigService` 能识别内容版本完成迁移（不能只按标题判断）。
- 当前改造任务见 `docs/claude-code-改造任务.md`，评审依据见 `D:\cosmic\claude_review_20261015\`。
