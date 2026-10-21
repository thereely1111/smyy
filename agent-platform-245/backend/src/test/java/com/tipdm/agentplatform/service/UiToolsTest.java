package com.tipdm.agentplatform.service;

import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiToolsTest {

    @Test
    void advancedFormRemainsGeneralPurposeHitlForm() {
        ToolBase tool = UiTools.advancedFormTool();

        assertEquals("ui_form_advanced", tool.getName());
        assertTrue(tool.isExternalTool());
        Map<String, Object> pages = object(object(tool.getParameters().get("properties")).get("pages"));
        assertFalse(pages.containsKey("maxItems"));
        assertTrue(pages.containsKey("items"));
        assertEquals(List.of("title", "pages"), tool.getParameters().get("required"));
    }

    @Test
    void gateUsesDedicatedSinglePageApprovalSchema() {
        ToolBase tool = UiTools.gateTool();

        assertEquals("ui_gate", tool.getName());
        assertTrue(tool.isExternalTool());

        Map<String, Object> pages = object(object(tool.getParameters().get("properties")).get("pages"));
        assertEquals(1, pages.get("minItems"));
        assertEquals(1, pages.get("maxItems"));

        Map<String, Object> page = object(list(pages.get("prefixItems")).getFirst());
        Map<String, Object> pageProperties = object(page.get("properties"));
        Map<String, Object> items = object(pageProperties.get("items"));
        List<Object> itemSchemas = list(items.get("prefixItems"));

        assertEquals("decision", propertyConst(itemSchemas.get(0), "key"));
        assertEquals("radio-group", propertyConst(itemSchemas.get(0), "kind"));
        assertEquals("feedback", propertyConst(itemSchemas.get(1), "key"));
        assertEquals("textarea", propertyConst(itemSchemas.get(1), "kind"));

        Map<String, Object> properties = object(tool.getParameters().get("properties"));
        // 闸门只承载决策表单：摘要/产物说明必须走模型正文，schema 里不留 description 入口。
        assertFalse(properties.containsKey("description"),
                "ui_gate must not accept a description; summaries belong in plain text output");

        var denyByDefault = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .build();
        assertEquals(PermissionBehavior.ALLOW,
                tool.checkPermissions(Map.of(), denyByDefault).block().getBehavior());
    }

    @Test
    void gateAcceptsMinimalDecisionOnlyPayload() {
        Map<String, Object> input = Map.of(
                "title", "闸门2 · 章节目录确认",
                "submitLabel", "提交",
                "pages", List.of(Map.of(
                        "key", "gate",
                        "title", "闸门决策",
                        "items", List.of(
                                Map.of("key", "decision", "label", "审核结论", "kind", "radio-group",
                                        "options", List.of("通过", "不通过"), "required", true),
                                Map.of("key", "feedback", "label", "修改意见", "kind", "textarea",
                                        "required", false)))));

        assertNull(UiTools.findOversizedGateField(input));
    }

    @Test
    void gateRejectsSummaryStuffedIntoFormFields() {
        StringBuilder summary = new StringBuilder();
        while (summary.length() < UiTools.MAX_GATE_FIELD_CHARS + 50) {
            summary.append("复审结论：目录层级与 JSON 不一致，需退回修改。");
        }
        Map<String, Object> input = Map.of("title", "闸门2", "description", summary.toString());

        assertNotNull(UiTools.findOversizedGateField(input));

        Map<String, Object> bloated = Map.of(
                "title", "闸门2",
                "pages", List.of(Map.of("key", "gate", "title", "闸门决策", "items", List.of(
                        Map.of("key", "decision", "label", "审核结论", "kind", "radio-group",
                                "options", List.of("通过", "不通过"), "required", true),
                        Map.of("key", "feedback", "label", summary.toString(),
                                "kind", "textarea", "required", false)))));

        assertNotNull(UiTools.findOversizedGateField(bloated));
    }

    private static Object propertyConst(Object schema, String property) {
        Map<String, Object> properties = object(object(schema).get("properties"));
        return object(properties.get(property)).get("const");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
