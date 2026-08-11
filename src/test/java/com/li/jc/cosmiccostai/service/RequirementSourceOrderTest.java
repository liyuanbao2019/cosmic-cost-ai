package com.li.jc.cosmiccostai.service;

import com.li.jc.cosmiccostai.model.ExtractedFunction;
import com.li.jc.cosmiccostai.model.FunctionSplitRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequirementSourceOrderTest {

    @Test
    void projectRequirementOrderPlacesScheduledCreateBeforeQueryAndKeepsMovementsTogether() {
        ExtractedFunction query = function("串实不符检测优化", "查询串实不符管理者视图数据", "REQ-L0010");
        ExtractedFunction scheduledCreate = function("串实不符检测优化", "定时汇总串实不符管理者视图数据", "REQ-L0009");

        List<FunctionSplitRow> rows = new ArrayList<FunctionSplitRow>(Arrays.asList(
                row("查询串实不符管理者视图数据", "E", "REQ-L0010"),
                row("定时汇总串实不符管理者视图数据", "E", "REQ-L0009"),
                row("查询串实不符管理者视图数据", "R", "REQ-L0008"),
                row("定时汇总串实不符管理者视图数据", "R", "REQ-L0015"),
                row("查询串实不符管理者视图数据", "X", "REQ-L0010"),
                row("定时汇总串实不符管理者视图数据", "W", "REQ-L0008")
        ));

        RequirementSourceOrder.sortRows(rows, Arrays.asList(query, scheduledCreate));

        assertEquals("定时汇总串实不符管理者视图数据", rows.get(0).getFunctionProcess());
        assertEquals(Arrays.asList("E", "R", "W"), movementTypes(rows.subList(0, 3)));
        assertEquals("查询串实不符管理者视图数据", rows.get(3).getFunctionProcess());
        assertEquals(Arrays.asList("E", "R", "X"), movementTypes(rows.subList(3, 6)));
    }

    @Test
    void sortsExtractedFunctionsByTheirOwnEvidenceInsteadOfModelCategoryOrder() {
        List<ExtractedFunction> functions = new ArrayList<ExtractedFunction>(Arrays.asList(
                function("模块", "导出客户", "REQ-L0014"),
                function("模块", "修改客户", "REQ-L0012"),
                function("模块", "新增客户", "REQ-L0010"),
                function("模块", "查询客户", "REQ-L0011")
        ));

        RequirementSourceOrder.sortFunctions(functions);

        assertEquals(Arrays.asList("新增客户", "查询客户", "修改客户", "导出客户"), names(functions));
    }

    @Test
    void usesLifecycleOrderOnlyWhenFunctionsShareTheSameRequirementPosition() {
        List<ExtractedFunction> functions = new ArrayList<ExtractedFunction>(Arrays.asList(
                function("模块", "导出客户", "REQ-L0010"),
                function("模块", "删除客户", "REQ-L0010"),
                function("模块", "修改客户", "REQ-L0010"),
                function("模块", "查询客户", "REQ-L0010"),
                function("模块", "新增客户", "REQ-L0010")
        ));

        RequirementSourceOrder.sortFunctions(functions);

        assertEquals(Arrays.asList("新增客户", "查询客户", "修改客户", "删除客户", "导出客户"),
                names(functions));
    }

    private ExtractedFunction function(String module, String name, String reference) {
        ExtractedFunction function = new ExtractedFunction(module, name, name);
        function.setSourceReferences(Arrays.asList(reference));
        return function;
    }

    private FunctionSplitRow row(String process, String movement, String reference) {
        FunctionSplitRow row = new FunctionSplitRow();
        row.setLevel1Module("串实不符检测优化");
        row.setLevel2Module("串实不符检测优化");
        row.setLevel3Module("串实不符检测优化");
        row.setFunctionProcess(process);
        row.setMovementType(movement);
        row.setSourceReferences(Arrays.asList(reference));
        return row;
    }

    private List<String> movementTypes(List<FunctionSplitRow> rows) {
        List<String> result = new ArrayList<String>();
        for (FunctionSplitRow row : rows) {
            result.add(row.getMovementType());
        }
        return result;
    }

    private List<String> names(List<ExtractedFunction> functions) {
        List<String> result = new ArrayList<String>();
        for (ExtractedFunction function : functions) {
            result.add(function.getFunction());
        }
        return result;
    }
}
