# cosmic-cost-ai

基于 COSMIC 功能点方法的软件造价 AI 辅助系统。系统支持从需求说明书或已拆分 Excel 导入项目，以同一条 AI 智能拆分流水线依次完成 COSMIC 初拆、AI 深度审核、安全建议采纳、复用度全表调整和规则初审，再由人工审核并一键导出附件1 / 附件2 / 附件3 送审材料。AI 深度审核当前通过配置开关暂时关闭；关闭时只跳过深审与建议采纳，其他阶段顺序不变。

## 当前能力

- 支持上传 `doc` / `docx` 需求文档，并为每次上传生成独立项目档案。
- 支持一次选择多份需求文档；先逐份确认送给 AI 的章节和最终正文，全部确认后再按项目并发执行拆分。
- 首页提供“需求级 / 项目级”拆分级别；项目级在保留每份需求原有送审材料的同时，自动汇总项目附件1至附件5及完整 ZIP。
- 支持直接导入已拆分的 `xls` / `xlsx` 附件3功能点拆分表，进入同一套审核、初审和导出流程。
- 提供需求说明书标准模板下载：`src/main/resources/static/templates/requirement-standard-template.docx`。
- 自动解析 Word 正文和表格文本，截取“需求内容 / 需求细则 / 业务功能 / 功能描述”等章节作为 AI 拆分输入。
- 支持查看 AI 调用提示词、复制提示词、编辑提示词并重新发起 AI 拆分。
- AI 拆分提示词会合并唯一的可编辑拆分校验规则，规则来源于 `src/main/resources/static/split-check-rules.md`。当前内容为在 v2.0 设计院规则上直接融入新疆 SFTP、数据库汇总、Kafka、实时接口及有效定时 E 口径的统一 v2.1，不存在通用版/新疆版切换。
- 基于 AI 结果生成 COSMIC 功能过程，并计算 Entry / Read / Write / Exit 与 CFP。
- 提供人工审核页，可修改、新增、复制、删除、勾选多行、批量修改功能点拆分行。
- 审核页支持搜索过滤、按一级模块 / 类型 / 复用度 / 初审提示筛选，以及紧凑 / 宽松视图切换。
- 支持“规则初审”，自动检查必填字段、E/R/W/X 枚举、复用度、功能过程结构和高风险表达。
- COSMIC 拆分完成后自动执行“复用度 AI 调整”，依据完整规则、全表关系和原文证据统一校准新增 / 复用 / 利旧；同一模块的新增、查询默认按新增，导入/上传作为新增数据的配套能力时按复用、没有新增功能时可按新增，修改、删除、导出、下载默认按复用统一落地。
- 功能过程按其自身 `REQ-L` 需求证据顺序稳定排列；原文前置的新增、定时生成或导入不会被模型归类到查询、修改、删除、导出之后，审核保存及 Excel / Word 导出使用同一顺序。
- 拆分行支持“功能描述”和“时序图”字段，Word 导出会按时序图生成业务逻辑图。
- 提供项目列表页，可查看历史项目、初审风险概况、导出状态，继续审核或删除项目。
- 需求级项目与项目级材料分开管理：单需求保留 `P...` 编号，项目级组合使用独立 `PJ...` 编号，批量执行过程使用 `BS...` 编号。
- 一键导出送审材料：附件3功能点拆分表、附件2业务需求信息汇总表、附件1需求说明书。
- 支持下载导出文件；Windows 桌面环境下可直接打开导出目录。
- 使用本地文件存储项目数据，不依赖数据库。

## 最新功能说明

### 项目列表

- 页面入口：`http://localhost:8080/projects.html`。
- 按更新时间倒序展示本地 `storage/projects` 下的历史项目。
- 展示项目来源类型：需求文档导入或已拆分 Excel 导入。
- 展示功能过程数、规则初审问题数、错误数、风险数和导出状态。
- 支持按项目名称、源文件名、项目 ID 搜索。
- 支持按来源类型、导出状态、初审状态筛选。
- 支持单个删除和批量删除；删除会移除项目存储目录下的上传文件、拆分结果和导出路径记录。
- “需求级拆分”页签可勾选多个已有最终拆分结果的需求，并复用这些结果创建项目级材料，不会重复调用 AI。
- “项目级材料”页签按独立 `PJ...` 编号展示项目名称、成员需求、拆分进度、材料版本和状态；点击 `PJ...` 可进入包含全部单需求成员的项目面板。
- 整合项目级材料与向项目添加已拆分需求均在当前页面弹窗完成，提交后原页面刷新，不通过修改 URL 在管理页和工作台之间来回跳转。
- 项目级项目支持单个及批量彻底删除，并执行两次确认；删除范围包括 `PJ...`、对应 `BS...`、全部 `Vn` 汇总材料、成员需求的上传/拆分数据及单需求导出材料。同一需求不会被允许重复加入两个项目级项目。

### 批量拆分

- “拆分级别”默认选择需求级；项目级支持一份或多份需求文档，统一按备案项目名称组织送审材料。
- 首页需求文档选择框支持分多次从不同目录追加 `doc` / `docx`，已选文件会累计保留；即使不同目录存在同名文件也不会被静默丢弃。
- 批次创建后只执行上传和解析，不会提前调用 AI。
- 文档解析与章节识别使用独立的本地准备线程池，不会被其他批次中长时间运行或等待 AI 的智能拆分任务阻塞。
- 批量确认面板逐份展示推荐章节、章节字数、解析警告和最终 AI 输入，支持人工修改。
- 每份文档确认时保存输入快照和内容哈希；全部文档确认且内容未变化后才允许启动。
- 文档之间按配置并发；单个文档内部与单需求模式共用 `SmartSplitPipelineService`，统一顺序为 COSMIC 初拆、AI 深度审核、安全建议采纳、复用度调整、规则初审；深审开关关闭时跳过中间两个可选阶段。
- 需求级批次完成后可一键为全部已完成需求生成各自的附件1、附件2、附件3及单需求 ZIP，并将所有单需求 ZIP 再打成一个批次总 ZIP 直接下载；个别材料生成失败时保留并打包成功项，同时展示完整失败原因。
- 单个批次不限制需求文档总数。多个批次同时运行时采用轮转调度；默认提供 1000 个文档解析槽、1000 个文档智能拆分槽，单批最多同时执行 1000 个，并由模型级 1000 个在途请求额度统一保护；超过并发数的需求继续排队。
- 服务重启只恢复批次进度展示，不会静默恢复解析、AI 拆分或项目材料生成；重启前未完成项标记为“已中断”，必须由用户点击“恢复中断项”后才重新排队，已完成结果继续保留。
- 某份文档失败不会中断其他文档，可只重试失败项；刷新页面可通过批次 ID 恢复进度。
- 项目级批次只要仍有失败需求，就将项目材料标记为“已阻断”，不会生成不完整的附件或 ZIP；已成功需求的结果继续保留，失败项全部重试成功后自动续接项目材料汇总。
- 项目级会先生成各需求原有附件1 / 2 / 3，再把全部最终审核行整合为一份项目需求说明书、业务需求汇总表和功能点拆分表。
- 项目级整合时会自动移除原始文件名开头用于文件排序的序号（如 `1.`、`13 `、`十一、`、`（15）`），再作为需求章节名称，避免与 Word 自动章节编号重复；原始文件名和单需求材料名称保持不变。
- 项目级附件4项目台账和附件5工作量评估申请表按项目总 CFP 汇总送审工作量；负责人、联系方式、预算、单价、供应商和签字等未知信息保持为空，送审前人工补充。
- 项目级五个附件生成后自动打包为 `项目名称-COSMIC送审材料.zip`；材料生成失败可单独重试，不重复运行已完成的 AI 拆分。
- 项目级全部需求拆分成功后同时生成两个独立产物：产物 1 是附件1至附件5组成的项目整合送审包；产物 2 是项目内每个需求各自附件1至附件3送审 ZIP 的全量总包。两个产物独立展示和下载，产物 2 个别需求生成失败时仍保留成功项并支持单独重试。
- 项目级项目支持继续添加已有拆分结果，也支持上传新需求后只对新增需求执行解析、确认和智能拆分；已完成成员不会重跑。
- 批量面板支持单独重新拆分一个已完成或失败的需求，其他成员结果保持不变；项目级重拆完成后生成新的 `Vn` 材料版本，旧版本目录继续保留。
- 需求文档初拆单次等待上限默认 360 秒，失败后最多再请求 1 次；两次请求始终使用 DeepSeek V4 Pro `reasoning_effort=max`，不会因超时降级为 `high`。批量面板实时展示第几次请求、已等待时间和本次剩余时间。
- 排队中或正在 AI 识别的单个需求可独立取消，不需要重启服务，也不会停止同批其他需求；请求真正结束后该项显示“已取消”，可直接重新拆分且仍使用 `max`。
- 需求级和项目级批次完成后均可打开“拆分摘要”，查看批次汇总 CFP、功能过程数、拆分行数及逐需求明细；摘要可直接下载为带公式合计、筛选和失败原因的 Excel，便于传递与归档。

### 已拆分 Excel 导入

- 上传入口在首页“已拆分 Excel”区域。
- 支持分多次从不同目录累计选择一个或多个 `xls` / `xlsx`；单文件需求级沿用单需求流程，多文件或项目级自动进入批量面板。
- Excel 模式显示“复用度 AI 调整”开关并默认勾选。勾选时执行“Excel 载入 → 复用度 AI 调整 → 规则初审”；取消时不调用深度审核或复用度调整模型，保留表内人工确认的新增、复用、利旧及 CFP，只执行规则初审。
- 单文件取消复用度 AI 调整后，规则校验通过会直接生成需求说明书、业务需求汇总表、功能点拆分表和送审 ZIP；如存在确定性错误则停在审核页提示修正。
- Excel 批次与原始需求文档批次共用摘要、失败隔离、单项重跑和全量单需求送审包；项目级还会自动生成附件 1–5 项目整合包及项目全量单需求送审包。
- 优先读取工作表 `2、功能点拆分表`；若找不到，会按表头特征自动识别包含功能点拆分数据的工作表。
- 默认从第 6 行读取数据，也会尝试根据表头自动定位数据起始行。
- 读取列范围为 `A:O`，其中 `N` 为功能描述，`O` 为时序图。
- 支持合并单元格场景：项目、模块、功能过程等层级列为空时会沿用上一行。
- 导入后会生成项目并写入 `function-split-rows.json`，可继续规则初审、人工审核和导出。
- 导入校验要求：`B一级模块`、`G功能过程`、`H子过程描述`、`I数据移动类型`、`J数据组`、`K数据属性` 必填。
- `I` 列可识别 `E/R/W/X` 以及输入、读取、写入、输出等中文别名；`L` 列复用度为空时按“新增”处理。

### 初审规则管理

- 首页流程区提供“初审规则管理”入口。
- 规则接口：
  - `GET /api/rules/split`
  - `PUT /api/rules/split`
- 默认规则文件：`src/main/resources/static/split-check-rules.md`，内容为《COSMIC 软件造价拆分基础规则 v2.1（设计院评审 + 新疆特殊场景沉淀版）》全文。它是 v2.0 基础规则的一次统一升级，不是第二套规则；运行时可编辑规则位于 `storage/config/split-check-rules.md`，已有 v2.0 规则会在首次读取时统一迁移为 v2.1。
- 可通过配置项覆盖规则路径：`COSMIC_SPLIT_RULES_PATH`。
- 保存后的规则会同时影响：
  - AI 拆分提示词。
  - 审核表“规则初审”中的高风险关键词识别。

### 规则初审

- 审核表上方提供“规则初审”按钮。
- 初审会检查：
  - 必填字段是否完整。
  - 数据移动类型是否为 `E/R/W/X`。
  - 复用度是否为 `新增 / 复用 / 利旧`。
  - 同一功能过程是否至少包含两个子过程。
  - 同一功能过程是否以初始触发 `E` 开始；末行必须是 `W/X`，末行不符合时按确定性错误处理。
  - 是否存在无依据的连续 `E`；本系统输出外部请求 `X` 后，同一业务目标内的同步响应允许再次记 `E`，异步回调/上报必须另拆功能过程。
  - 不设置“最多 4 行”限制；长链路逐条核对原文、软件边界和同一业务目标依据。
  - 复用度与 CFP 是否符合新增=`1`、复用=`0.33`、利旧=`0`；同时执行同功能过程和跨功能过程三要素语义查重，并区分功能级重复/包含=`0`与子过程级复用=`0.33`。
  - 数据属性是否可独立审查、是否使用“同上/见上”；属性可来自原文表格、接口参数和正文明确业务数据项，不允许虚构字段。新疆项目暂不因“字段名（待确认）”混合表达提示风险。
  - 数据属性仍为“待确认”时作为材料待补充风险展示；同一功能过程中相同数据组已有明确属性时提示完整复用，无载荷定时触发使用“无业务数据”。
  - 是否存在翻页、勾选、图层切换、自动填充、高亮渲染、技术日志、开发术语等非独立数据移动风险。
  - 是否把查询与展示、单字段/单维度操作或同一对象的相同环节过度拆分。
  - 是否命中 `N/NM/数字行号/O/OLD/UIUE/C/NR/算法/需求文档不涉及` 等设计院历史评审批注；新疆项目不因“读取相关数据/保存处理结果/返回处理结果”等子过程表述本身产生风险提示。
  - 常见业务动作是否符合已认可样例，例如查询 `E-R-X`、新增/删除 `E-W-X`、修改/导入 `E-R-W-X`。
  - 导出文件是否留存、消息结果是否保存、定时任务是否跨边界输出，以及接口调用方向与 `R/W/X` 是否一致。
  - 是否命中高风险表达或利旧风险表达。
- 初审结果会同步到审核表的“风险原因”和“修改建议”列，便于筛选和定位。
- 门禁统一区分“确定性错误”和“结构风险”：确定性错误必须修正并阻断保存/导出；结构风险用于提示人工判断，不阻断送审材料导出。

### 复用度 AI 调整

- 这是 AI 智能拆分的内部质量阶段，不依赖已屏蔽的逐行 AI 复核功能；页面不再把它作为必须人工点击的独立流程节点。
- 每次把当前完整拆分表作为同一判断范围，先识别功能级重复/包含，再识别子过程级复用，最后识别真实跨层、跨边界的新增。
- 目标是让送审口径与设计院最终评审更一致、降低后续核减率，不以提高或压低 CFP 为目标。
- 跨功能过程按“首次实现、后续复用”处理：前面首次出现的子过程保留新增/1；后面不同用户目标再次使用时，两项相同，或三项完全相同但整个功能过程不重复，自动设为复用/0.33；只有完整用户目标及整组 E/R/W/X 重复/被包含才进入利旧/0 判断。
- 服务端只采纳通过证据闸门的建议：复用必须引用有效行并匹配至少两项审核三要素；利旧必须覆盖整个功能过程；恢复新增必须说明真实边界差异。
- 新疆统一规则中的有效定时/时钟/系统事件初始 E 由模型按 `XJ-TIMER-E` 结合当前需求或 Excel 当前行证据判断；服务端只允许该规则作用于功能过程首行 E，并要求后续存在真实 W/X。通过后只把该 E 调整为复用/0.33，后续 R/W/X 不联动。
- 模型建议、采纳数、拦截数和 CFP 调整前后都会在流程完成信息中展示。

### DeepSeek 专项提示词与调用策略

- 当前模型的 `provider`、`model` 或 `baseUrl` 识别为 DeepSeek 时，拆分和复用度 AI 调整自动切换到 `deepseek-cosmic-v1`；深度审核专项提示词继续保留，在重新启用深审开关后生效。
- DeepSeek 专项版不保存第二份 COSMIC 业务规则。各任务只引用 `SplitRuleConfigService` 提供的当前新疆统一 v2.1 规则及同一规则版本号，专项执行协议仅规定处理顺序和输出格式。
- 拆分固定返回 `{"functions":[]}`，复用度 AI 调整固定返回 `{"decisions":[]}`，深度审核固定返回 `{"suggestions":[]}`；功能描述也返回严格 JSON 对象，调用统一启用 DeepSeek `json_object`。
- 选择 DeepSeek 后，需求文档初拆统一启用 Thinking 并使用最强 `reasoning_effort=max`；单需求与批量需求共用该设置。复用度全表判断继续使用 `reasoning_effort=high`，功能描述生成保持结构化非 Thinking 模式；深度审核重新启用时使用 `high`。最终结果只读取 JSON `content`，不接收推理过程。
- DeepSeek 请求使用独立 System/User 消息：System 约束唯一规则源和 JSON 输出，User 携带当前任务、完整规则、审核数据及原文证据。
- 初审“复用与 CFP”跨功能两项相同候选由独立复用度调整节点按全表同一口径自动设为复用、CFP=0.33，并记录参考拆分数据行；正常流水线中后续初审不再重复提示该风险。
- E 类描述按真实发起方自然表达：用户/UI 主动触发使用“发起/提交”，外部平台上报或回调使用“接收”，外部同步响应使用“获取同步响应数据”；本地保存时会修正明确的“接收+功能过程名+请求”机械模板。

## 导出规则

### 附件3：功能点拆分表

- 使用 `templates` 目录下以“附件3”开头的固定 Excel 模板。
- 写入模板的第 2 个工作表：`2、功能点拆分表`。
- 数据从第 6 行开始写入。
- 写入列范围为 `A:N`：
  - `A:G`：项目 / 模块 / 用户 / 触发事件 / 功能过程等层级信息。
  - `H:M`：子过程描述、E/R/W/X、数据组、数据属性、复用度、CFP。
  - `N`：规则初审发现的风险原因；同一行有多个原因时分行展示，整列文字使用红色粗体，列标题会提示“提交送审前务必删除”。
- `A:G` 会按父级层级合并相邻相同内容。
- 风险着色仅应用于不参与层级合并的 `H:N`，不会拆开 `A:G` 的合并区域。
- 存在结构风险时仍允许导出，相关行及 `N` 列风险原因会保留着色供人工审核；只有确定性错误会阻断导出。
- `L` 列“复用度”提供下拉选项：`新增`、`复用`、`利旧`。
- `M` 列 CFP 由复用度自动计算：新增 = `1`，复用 = `1/3`，利旧 = `0`。
- 如果目标文件已存在，会自动生成带 `yyyyMMddHHmmss` 后缀的新文件，避免覆盖正在被 WPS / Excel / Word 占用的文件。

### 附件2：业务需求信息汇总表

- 使用 `templates` 目录下以“附件2”开头的固定 Excel 模板。
- 按一级 / 二级 / 三级模块聚合审核后的拆分行。
- 自动写入功能需求列表、功能点数汇总和总 CFP。
- 复用度为“新增”的需求标记为新增，其余标记为优化。

### 附件1：需求说明书

- 使用 `templates` 目录下以“附件1”开头的 Word 模板。
- 自动替换封面项目名称和年月。
- 读取已导出的附件3，确保 Word 内容来自最终审核后的拆分表。
- 按一级模块、二级模块、三级模块和功能过程生成“功能需求”章节。
- 根据“时序图”字段生成 PlantUML PNG 并插入 Word。
- 每个功能过程会写入业务逻辑步骤和功能描述。
- Word 导出前必须先导出 Excel。

## 目录结构

```text
cosmic-cost-ai
|-- src
|   `-- main
|       |-- java
|       |   `-- com/li/jc/cosmiccostai
|       |       |-- common
|       |       |-- controller
|       |       |-- dto
|       |       |-- model
|       |       `-- service
|       `-- resources
|           |-- application.yml
|           `-- static
|               |-- index.html
|               |-- projects.html
|               |-- split-check-rules.md
|               `-- templates
|                   `-- requirement-standard-template.docx
|-- templates
|   |-- 附件1-XX项目需求说明书V1.0.docx
|   |-- 附件2-业务需求信息汇总表V1.0.xlsx
|   `-- 附件3-XX项目功能点拆分表V1.0.xlsx
|-- prompts
|-- sample
|-- storage
|-- output
|-- docs
|-- pom.xml
`-- README.md
```

| 目录 | 说明 |
| --- | --- |
| `src/main/java` | Spring Boot 后端代码 |
| `src/main/resources/static` | 前端工作台、项目列表、初审规则和下载模板 |
| `templates` | 附件1 / 附件2 / 附件3 固定导出模板 |
| `storage` | 项目元数据、上传文件、解析文本、AI 结果、审核结果 |
| `output` | 导出的 Excel / Word 送审材料 |
| `docs` | 架构、部署和存储设计文档 |
| `prompts` | AI 提示词相关材料 |

## 技术栈

- 后端：Java 8、Spring Boot 2.7.18、Maven、Spring Web、Spring Validation、Spring Actuator。
- API 文档：Springdoc OpenAPI / Swagger UI。
- AI：LangChain4j、OpenAI 兼容接口，默认按 DeepSeek OpenAI 兼容接口配置。
- 文档处理：Apache POI、Apache Tika、PDFBox、poi-tl、PlantUML。
- 存储：本地 JSON / 文本 / 原始文件，不使用 JDBC、JPA、MyBatis 或外部数据库。

## 本地启动

环境要求：

- JDK 8
- Maven 3.6.1 或更高版本

启动：

```bash
mvn spring-boot:run
```

或先打包再启动：

```bash
mvn clean package -DskipTests
java -jar target/cosmic-cost-ai-0.1.0-SNAPSHOT.jar
```

启动后访问：

- 工作台：`http://localhost:8080/`
- 项目列表：`http://localhost:8080/projects.html`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- Health：`http://localhost:8080/actuator/health`

## 配置

默认配置位于 `src/main/resources/application.yml`：

```yaml
cosmic:
  storage:
    base-dir: storage
  output:
    base-dir: output
  template:
    excel-path: ${COSMIC_EXCEL_TEMPLATE:templates/附件3-XX项目功能点拆分表V1.0.xlsx}
    word-path: ${COSMIC_WORD_TEMPLATE:templates/附件1-XX项目需求说明书V1.0.docx}
  ai:
    provider: deepseek
    base-url: ${OPENAI_BASE_URL:https://api.deepseek.com/v1}
    api-key: ${OPENAI_API_KEY:${DEEPSEEK_API_KEY:}}
    model: ${OPENAI_MODEL:deepseek-v4-pro}
    timeout-seconds: ${OPENAI_TIMEOUT_SECONDS:180}
    max-retries: ${OPENAI_MAX_RETRIES:2}
    extraction:
      timeout-seconds: ${COSMIC_AI_EXTRACTION_TIMEOUT_SECONDS:360}
      max-retries: ${COSMIC_AI_EXTRACTION_MAX_RETRIES:1}
  rules:
    split-rules-path: ${COSMIC_SPLIT_RULES_PATH:classpath:/static/split-check-rules.md}
```

常用环境变量：

```bash
OPENAI_BASE_URL=https://api.deepseek.com/v1
OPENAI_API_KEY=your-api-key
OPENAI_MODEL=deepseek-v4-pro
OPENAI_TIMEOUT_SECONDS=180
OPENAI_MAX_RETRIES=2
COSMIC_AI_EXTRACTION_TIMEOUT_SECONDS=360
COSMIC_AI_EXTRACTION_MAX_RETRIES=1
COSMIC_SPLIT_RULES_PATH=classpath:/static/split-check-rules.md
```

也可以只配置：

```bash
DEEPSEEK_API_KEY=your-api-key
```

模板说明：

- 附件3优先使用 `COSMIC_EXCEL_TEMPLATE` 指定的模板；附件2会自动在 `templates` 目录查找以“附件2”开头的 `.xlsx`。
- 附件1优先使用 `COSMIC_WORD_TEMPLATE` 指定的模板。
- 服务器部署时必须随应用一起上传 `templates` 目录。
- 不要覆盖服务器上的 `storage` 和 `output`，除非确认要清理历史项目和导出材料。

## 主要流程

### 从需求文档开始

```text
上传 doc/docx 需求文档
  -> 解析文档并预览需求细则
  -> 确认 AI 输入（可查看 / 编辑提示词）
  -> AI 智能拆分
       COSMIC 初拆
       复用度 AI 全表调整
       规则初审
  -> 人工审核与导出
  -> 导出附件3与附件2
  -> 导出附件1
  -> 下载或打开送审材料目录
```

### 从已拆分 Excel 开始

```text
上传 xls/xlsx 附件3拆分表
  -> AI 智能校准
       复用度 AI 全表调整
       规则初审
  -> 人工审核与导出
  -> 导出附件3与附件2
  -> 导出附件1
  -> 下载或打开送审材料目录
```

## 页面入口

- 首页工作台：`http://localhost:8080/`
- 项目列表：`http://localhost:8080/projects.html`
- 需求说明书标准模板：`http://localhost:8080/templates/requirement-standard-template.docx`

首页工作台能力：

- 上传需求文档并选择项目名称。
- 下载标准需求说明书模板。
- 导入已拆分 Excel。
- 单文档点击“开始拆分”后先解析并确认 AI 输入，再由持久化后台任务执行共享智能拆分流水线；刷新页面可恢复进度。
- 多文档点击“导入并确认”后进入批量确认面板；所有 AI 输入确认完成后，点击“开始并发拆分”。
- 点击流程中的“解析”可展开 / 收起解析预览。
- 页面流程收敛为“上传与解析 → AI 智能拆分 → 人工审核与导出”；点击“AI 智能拆分”默认查看三个内部环节的实时进度和结果统计，提示词查看/编辑保留为进度面板中的次要入口。
- 点击“初审规则管理”可查看和保存拆分规则。
- 规则初审和复用度调整由 AI 智能拆分自动执行；人工修改表格后仍可按需重新校准。
- 在审核表中可直接编辑单元格；长文本字段会打开编辑弹窗。
- 支持新增行、复制单行、复制选中、删除选中和批量修改。
- 修改复用度后自动刷新 CFP。
- 点击“导出送审材料”会依次生成附件3、附件2和附件1。
- 三份送审材料全部生成后会自动打包为 ZIP；ZIP 使用需求说明书的文件名，仅将 `.docx` 扩展名替换为 `.zip`。
- AI 深度审核和安全建议采纳当前从新流程中跳过，页面不展示相关节点，也不会自动发起深审模型请求。
- 可通过 `COSMIC_SMART_SPLIT_DEEP_REVIEW_ENABLED=true` 恢复原深审流水线；深审服务、提示词、分批状态和接口代码均继续保留。

## API 列表

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/files/upload` | POST | 上传需求说明书，支持 `doc` / `docx` |
| `/api/files/upload-split-excel` | POST | 上传已拆分附件3，支持 `xls` / `xlsx` |
| `/api/projects` | GET | 获取本地历史项目列表 |
| `/api/projects/{projectId}` | DELETE | 删除单个项目 |
| `/api/projects/batch-delete` | POST | 批量删除项目 |
| `/api/projects/batch-split` | POST | 创建批量拆分任务并异步解析所选需求文档 |
| `/api/projects/batch-split/project-groups` | POST / GET | 使用已有需求创建项目级项目 / 查询项目级项目列表 |
| `/api/projects/batch-split/project-groups/{projectGroupId}` | GET / DELETE | 按 `PJ...` 编号查询项目级项目及全部需求成员 / 级联删除项目及全部成员数据 |
| `/api/projects/batch-split/project-groups/batch-delete` | POST | 批量级联删除多个项目级项目及全部成员数据 |
| `/api/projects/batch-split/{batchId}` | GET | 查询批次准备、确认及并发执行进度 |
| `/api/projects/batch-split/{batchId}/summary` | GET | 汇总已完成需求的最终功能过程、拆分行及 CFP |
| `/api/projects/batch-split/{batchId}/summary/download` | GET | 下载需求级或项目级批次 CFP 摘要 Excel |
| `/api/projects/batch-split/{batchId}/items` | POST | 向项目级项目追加已有拆分需求或新上传需求 |
| `/api/projects/batch-split/{batchId}/items/{projectId}/rerun` | POST | 只重新拆分指定需求，保留批次内其他成功结果 |
| `/api/projects/batch-split/{batchId}/items/{projectId}/cancel` | POST | 只取消指定需求的排队或 AI 识别请求，其他需求继续执行 |
| `/api/projects/batch-split/{batchId}/items/{projectId}/ai-input` | PUT | 保存并确认单份文档的章节和最终 AI 输入 |
| `/api/projects/batch-split/{batchId}/start` | POST | 全部文档确认后启动并发拆分 |
| `/api/projects/batch-split/{batchId}/retry` | POST | 仅重试批次中的失败文档 |
| `/api/projects/batch-split/{batchId}/materials/retry` | POST | 仅重试失败的项目级送审材料生成 |
| `/api/projects/batch-split/{batchId}/materials/{type}/download` | GET | 下载项目级附件1至附件5或完整 ZIP |
| `/api/projects/batch-split/{batchId}/requirement-materials` | POST | 生成或重新生成批次内全部单需求送审材料总包 |
| `/api/projects/batch-split/{batchId}/requirement-materials/download` | GET | 下载需求级批次整合包或项目级产物 2 |
| `/api/projects/{projectId}/parse` | POST | 解析需求说明书 |
| `/api/projects/{projectId}/extract` | POST | 使用默认提示词执行 AI 功能点提取 |
| `/api/projects/{projectId}/ai-prompt` | GET | 获取当前项目 AI 提示词和模型名 |
| `/api/projects/{projectId}/ai-prompt/extract` | POST | 使用编辑后的提示词重新执行 AI 提取 |
| `/api/projects/{projectId}/cosmic` | POST | 执行 COSMIC 分析 |
| `/api/projects/{projectId}/review` | GET | 加载人工审核数据 |
| `/api/projects/{projectId}/review` | PUT | 保存人工审核后的拆分表 |
| `/api/projects/{projectId}/review/validate` | POST | 对当前审核行执行规则初审 |
| `/api/projects/{projectId}/reuse-degree/adjust` | POST | 对完整拆分表执行复用度 AI 调整并保存证据充分的变更 |
| `/api/projects/{projectId}/smart-split` | POST | 创建单需求持久化 AI 智能拆分任务；文档与 Excel 模式共用统一流水线 |
| `/api/projects/{projectId}/smart-split` | GET | 查询智能拆分内部阶段、进度和最终初审结果；深审关闭时返回 `deepSplitStatus=DISABLED` |
| `/api/projects/{projectId}/review/deep-split` | POST | 创建异步、分批的 AI 深度审校任务 |
| `/api/projects/{projectId}/review/deep-split/{jobId}` | GET | 查询任务进度和已完成建议 |
| `/api/projects/{projectId}/review/deep-split/{jobId}/retry` | POST | 仅重试超时或失败批次 |
| `/api/projects/{projectId}/export/excel` | POST | 导出附件3功能点拆分表，并同步导出附件2业务需求信息汇总表 |
| `/api/projects/{projectId}/export/word` | POST | 导出附件1需求说明书 |
| `/api/projects/{projectId}/export/archive` | POST | 将附件1、附件2、附件3打包为送审材料 ZIP |
| `/api/projects/{projectId}/exports/{fileType}/info` | GET | 获取导出文件路径、下载链接和打开目录能力 |
| `/api/projects/{projectId}/exports/{fileType}/download` | GET | 下载导出文件 |
| `/api/projects/{projectId}/exports/{fileType}/open-directory` | POST | Windows 环境下打开导出目录 |
| `/api/rules/split` | GET | 获取当前拆分初审规则 |
| `/api/rules/split` | PUT | 保存拆分初审规则 |

`fileType` 支持：

- `excel`：附件3功能点拆分表
- `business`：附件2业务需求信息汇总表
- `word`：附件1需求说明书
- `archive`：包含附件1、附件2、附件3的送审材料 ZIP

## API 示例

上传需求文档：

```bash
curl -F "file=@sample/example.docx" \
  -F "projectName=演示项目" \
  http://localhost:8080/api/files/upload
```

导入已拆分 Excel：

```bash
curl -F "file=@sample/split.xlsx" \
  -F "projectName=演示项目" \
  http://localhost:8080/api/files/upload-split-excel
```

项目列表与删除：

```bash
curl http://localhost:8080/api/projects
curl -X DELETE http://localhost:8080/api/projects/{projectId}
curl -X POST http://localhost:8080/api/projects/batch-delete \
  -H "Content-Type: application/json" \
  -d "[\"projectId-1\",\"projectId-2\"]"
```

解析、AI 拆分与 COSMIC 分析：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/parse
curl -X POST http://localhost:8080/api/projects/{projectId}/extract
curl -X POST http://localhost:8080/api/projects/{projectId}/cosmic
```

查看 AI 提示词：

```bash
curl http://localhost:8080/api/projects/{projectId}/ai-prompt
```

使用编辑后的提示词重新拆分：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/ai-prompt/extract \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"这里放编辑后的完整提示词\"}"
```

规则初审：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/review/validate \
  -H "Content-Type: application/json" \
  -d @function-split-rows.json
```

获取和保存初审规则：

```bash
curl http://localhost:8080/api/rules/split
curl -X PUT http://localhost:8080/api/rules/split \
  -H "Content-Type: application/json" \
  -d "{\"content\":\"# 拆分校验规则\n...\"}"
```

导出送审材料：

```bash
curl -X POST http://localhost:8080/api/projects/{projectId}/export/excel
curl -X POST http://localhost:8080/api/projects/{projectId}/export/word
```

下载导出文件：

```bash
curl -O -J http://localhost:8080/api/projects/{projectId}/exports/excel/download
curl -O -J http://localhost:8080/api/projects/{projectId}/exports/business/download
curl -O -J http://localhost:8080/api/projects/{projectId}/exports/word/download
```

创建项目级批次并下载完整送审材料：

```bash
curl -X POST http://localhost:8080/api/projects/batch-split \
  -H "Content-Type: application/json" \
  -d '{"projectIds":["projectId-1","projectId-2"],"splitLevel":"PROJECT"}'
curl -O -J http://localhost:8080/api/projects/batch-split/{batchId}/materials/archive/download
curl -O -J http://localhost:8080/api/projects/batch-split/{batchId}/requirement-materials/download
```

## 存储文件

上传并处理后会在项目目录下生成：

```text
storage/projects/{projectId}
|-- metadata.json
|-- document-text.txt
|-- requirement-detail.txt
|-- ai-prompt.txt
|-- ai-prompt-user-edited.flag
|-- extracted-functions.json
|-- cosmic-results.json
|-- function-split-rows.json
`-- files
    |-- original.{doc|docx}
    `-- split-review.{xls|xlsx}
```

说明：

- `metadata.json`：项目元数据、状态、源文件、存储路径和导出路径。
- `document-text.txt`：文档解析后的纯文本。
- `requirement-detail.txt`：截取后的需求细则文本。
- `ai-prompt.txt`：当前项目 AI 拆分提示词。
- `ai-prompt-user-edited.flag`：标记提示词是否由用户编辑过。
- `extracted-functions.json`：AI 提取的功能过程。
- `cosmic-results.json`：COSMIC 统计结果。
- `function-split-rows.json`：人工审核后的功能点拆分表行数据。
- `split-review.{xls|xlsx}`：从已拆分 Excel 导入项目时保存的源文件。
- `deep-split/{jobId}/state.json`：AI 深度审校的原始审核行、分批状态、失败原因和累计建议，用于进度查询与失败批次重试。
- `smart-split/state.json`：单需求 AI 智能拆分的当前阶段、进度、深审开关状态和最终规则初审统计。
- `storage/batch-split/{batchId}/state.json`：批量拆分的逐文档准备、确认和执行状态。
- `storage/batch-split/{batchId}/inputs/{projectId}.txt`：人工确认后冻结的 AI 输入快照。
- 项目级 `PJ...` 编号、成员关系、材料版本、汇总 CFP、三级需求数量及当前附件下载路径也持久化在批次 `state.json`；服务重启后只恢复展示，不会静默拉起未完成任务。

导出文件生成在：

```text
output/{projectId}
|-- 附件3-...的需求功能点拆分表V1.0.xlsx
|-- 附件2-...的业务需求信息汇总表V1.0.xlsx
`-- 附件1-....docx
```

项目级项目按材料版本生成独立目录，旧版本不会被覆盖：

```text
output/project-materials/{projectGroupId}-V{version}
|-- 附件1-{项目名称}需求说明书.docx
|-- 附件2-{项目名称}业务需求信息汇总表V1.0.xlsx
|-- 附件3-{项目名称}功能点拆分表V1.0.xlsx
|-- 附件4-{项目名称}台账V1.0.xlsx
|-- 附件5-{项目名称}定制化软件工作量评估申请表V1.0.xlsx
`-- {项目名称}-COSMIC送审材料.zip
```

如果同名文件已存在，会生成：

```text
附件3-...的需求功能点拆分表V1.0-yyyyMMddHHmmss.xlsx
附件2-...的业务需求信息汇总表V1.0-yyyyMMddHHmmss.xlsx
附件1-...-yyyyMMddHHmmss.docx
```

## 部署

服务器部署请参考：

- [服务器部署文档](docs/server-deployment.md)

部署时至少需要上传：

- `target/cosmic-cost-ai-0.1.0-SNAPSHOT.jar`
- `templates/`
- 必要的环境变量或 `application.yml` 配置

注意：

- 需求说明书标准模板位于静态资源目录，随 JAR 打包。
- 如果使用外部 `COSMIC_SPLIT_RULES_PATH`，需要确保服务进程有读取权限；如果前端保存规则，也需要写入权限。
- 不要覆盖服务器上的 `storage` 和 `output`，除非确认要清理历史数据。

## 相关文档

- [服务器部署文档](docs/server-deployment.md)
- [项目目录结构](docs/project-structure.md)
- [文件型数据存储设计](docs/file-storage-design.md)
- [MVP 开发范围](docs/mvp-scope.md)
- [COSMIC 附件自动生成系统架构设计](docs/COSMIC附件自动生成系统架构设计.md)
