package com.li.jc.cosmiccostai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovementDescriptionNormalizerTest {

    @Test
    void rewritesMechanicalHumanQueryIntoNaturalBusinessAction() {
        assertEquals("发起申诉不符管理员视图数据查询，提交地市、工单类型等查询条件",
                MovementDescriptionNormalizer.normalizeEntryDescription(
                        "接收申诉不符管理员视图数据查询请求，包含地市、工单类型等查询条件",
                        "发起者：管理员\n接收者：本系统", "UI->后台服务"));
    }

    @Test
    void keepsExternalPlatformReportsAsInboundData() {
        assertEquals("接收SEQ常驻小区定界结果上报",
                MovementDescriptionNormalizer.normalizeEntryDescription(
                        "接收SEQ常驻小区定界结果上报",
                        "发起者：SEQ平台\n接收者：网优工作台", "SEQ平台->网优工作台"));
    }

    @Test
    void rewritesHumanSubmittedConditionsWithoutInventingARequest() {
        assertEquals("提交订单查询条件",
                MovementDescriptionNormalizer.normalizeEntryDescription(
                        "接收订单查询条件", "发起者：业务人员\n接收者：本系统", "UI->后台服务"));
    }
}
