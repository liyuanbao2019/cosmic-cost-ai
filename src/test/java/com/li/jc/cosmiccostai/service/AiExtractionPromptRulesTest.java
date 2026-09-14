package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.CosmicMovement;
import com.li.jc.cosmiccostai.model.ExtractedFunction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiExtractionPromptRulesTest {

    @Test
    void promptIncludesSplitCheckRulesMarkdown() throws Exception {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        Method method = AiExtractionService.class.getDeclaredMethod("buildPrompt", String.class, String.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(service, "prompt-project", "用户提交规则校验需求");

        assertTrue(prompt.contains("【内部拆分校验与可计量改写规则】"));
        assertTrue(prompt.contains("COSMIC 软件造价拆分基础规则 v2.1（设计院评审 + 新疆特殊场景沉淀版）"));
        assertTrue(prompt.contains("规则校验"));
        assertTrue(prompt.contains("所有功能过程、子过程、数据组、数据属性必须能从需求原文"));
        assertTrue(prompt.contains("需求信息不足时，应输出“待确认事项”"));
        assertTrue(prompt.contains("绝不为了增加功能点而过度拆分"));
        assertTrue(prompt.contains("不得新增无业务依据的时间、枚举值或属性"));
        assertTrue(prompt.contains("用户查询 E-R-X；新增/删除 E-W-X；修改、导入和动态导出文件 E-R-W-X"));
        assertTrue(prompt.contains("临时文件也必须拆开 W 和 X"));
        assertTrue(prompt.contains("不得只写“生成文件并返回”的单个 X"));
        assertTrue(prompt.contains("下载或预览已经存在且本次不重新生成的文件可为 E-R-X"));
        assertTrue(prompt.contains("被调用查询 E-R-X，被调用接收同步数据 E-W-X"));
        assertTrue(prompt.contains("E 已携带查询所需参数并直接通过 X 发出请求"));
        assertTrue(prompt.contains("响应进入本系统可再次记 E"));
        assertTrue(prompt.contains("除上述已明确的导入/动态导出文件 W-X 规则外"));
        assertTrue(prompt.contains("异步业务回调必须区分"));
        assertTrue(prompt.contains("禁止用“接收+功能过程名+请求”的机械拼接方式"));
        assertTrue(prompt.contains("用户/UI 主动操作写“发起XXX查询"));
        assertTrue(prompt.contains("外部平台上报、回调才写"));
        assertTrue(prompt.contains("ReturnCode/ReturnMessage 是本系统向调用方返回的 X"));
        assertTrue(prompt.contains("\"movements\""));
        assertTrue(prompt.contains("正文中的“六级地址、故障原因、ret、P01、U13”"));
        assertTrue(prompt.contains("相同数据组传递同一批数据时"));
        assertTrue(prompt.contains("无载荷的定时/时钟触发 E 填“无业务数据”"));
        assertTrue(prompt.contains("新疆项目暂不因“字段名（待确认）”混合表达提示风险"));
        assertTrue(prompt.contains("不因“配置”一词单独提示风险"));
        assertTrue(prompt.contains("至少输出 2 个 movements"));
        assertTrue(prompt.contains("最后一个必须是 W 或 X"));
        assertTrue(prompt.contains("不同功能过程中"));
        assertTrue(prompt.contains("三要素全部相同且没有跨层/跨边界"));
        assertTrue(prompt.contains("子过程数量不再设置“最多 4 行”硬限制"));
        assertTrue(prompt.contains("多外部系统调用、多个独立持久数据组读写可自然超过 4 条"));
        assertFalse(prompt.contains("2—4 个 movements"));
        assertTrue(prompt.contains("翻页、勾选/取消勾选、图层切换"));
        assertTrue(prompt.contains("校验、计算、解析、匹配、过滤、排序是数据操作"));
        assertFalse(prompt.contains("至少 6 个数据属性"));
        assertTrue(prompt.contains("禁止虚构字段"));
        assertTrue(prompt.contains("N/NM/数字行号/O/OLD/UIUE/C/NR/算法/需求文档不涉及"));
        assertTrue(prompt.contains("新疆项目不因“读取相关数据”“保存处理结果”“返回处理结果”等表述本身判定风险或核减"));
        assertTrue(prompt.contains("新疆专项：SFTP 文件接入及共享"));
        assertTrue(prompt.contains("新疆专项：数据库中数据汇总"));
        assertTrue(prompt.contains("新疆专项：Kafka 接入及共享"));
        assertTrue(prompt.contains("新疆专项：实时交互接口"));
        assertTrue(prompt.contains("有效的定时任务触发 E 默认按复用（0.33 CFP）处理"));
        assertTrue(prompt.contains("functions 必须按功能自身 sourceReferences 对应的 REQ-L 原文先后顺序输出"));
        assertTrue(prompt.contains("同一功能过程的全部 movements 必须连续"));
        assertTrue(prompt.contains("【需求细则】"));
        assertTrue(prompt.contains("[REQ-L0001] 用户提交规则校验需求"));
        assertTrue(prompt.contains("\"sourceReferences\":[\"REQ-L0001\"]"));
    }

    @Test
    void deepSeekPromptUsesDedicatedProtocolAndTheSameCompleteRuleSource() throws Exception {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "https://api.deepseek.com/v1", "", "deepseek-flash", 1, 0,
                "classpath:/static/split-check-rules.md");
        Method method = AiExtractionService.class.getDeclaredMethod("buildPrompt", String.class, String.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(service, "prompt-project", "用户提交规则校验需求");
        String ruleTitle = "COSMIC 软件造价拆分基础规则 v2.1（设计院评审 + 新疆特殊场景沉淀版）";

        assertTrue(prompt.contains("DeepSeek 专项执行协议"));
        assertTrue(prompt.contains("deepseek-cosmic-v1"));
        assertTrue(prompt.contains("唯一规则源：SplitRuleConfigService 当前生效完整规则"));
        assertTrue(prompt.contains("顶层只能包含 functions 数组"));
        assertTrue(prompt.contains("不得把原文前置的新增、定时生成或导入功能移到查询、修改、删除、导出、下载之后"));
        assertTrue(prompt.contains("更新调拨分派单状态为已出库，并更新库存及全生命周期流转记录"));
        assertTrue(prompt.contains("调拨分派单、库存、全生命周期流转记录 3 个独立 W"));
        assertTrue(prompt.contains("{\"functions\":[]}"));
        assertTrue(prompt.indexOf(ruleTitle) >= 0);
        assertTrue(prompt.indexOf(ruleTitle) == prompt.lastIndexOf(ruleTitle));
        assertFalse(prompt.contains("输出必须是严格JSON数组"));
    }

    @Test
    void attachesStableRequirementReferencesToFunctionsAndMovements() {
        AiExtractionService service = new AiExtractionService(new FileStorageService("storage"),
                "", "", "test-model", 1, 0, "classpath:/static/split-check-rules.md");
        ExtractedFunction function = new ExtractedFunction("订单管理", "查询订单状态", "读取并返回订单状态");
        CosmicMovement movement = new CosmicMovement();
        movement.setType("R");
        movement.setDescription("读取订单状态");
        movement.setDataGroup("订单表");
        movement.setDataAttributes("订单编号、订单状态");
        function.setMovements(Arrays.asList(movement));

        service.attachRequirementEvidence(Arrays.asList(function),
                "公告管理支持新增公告。\n订单管理支持按订单编号查询订单状态并返回结果。");

        assertTrue(function.getSourceReferences().contains("REQ-L0002"));
        assertTrue(function.getEvidence().contains("查询订单状态"));
        assertTrue(movement.getSourceReferences().contains("REQ-L0002"));
        assertTrue(movement.getEvidence().contains("订单编号"));
    }
}
