package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.domain.AlertChannel;
import com.tipdm.agentplatform.service.channel.ChannelService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 渠道管理（用户级出站渠道：企业微信 / 钉钉 / 飞书 / 通用 webhook）。
 *
 * <p>路径形状兼容既有前端 {@code channelApi}（含列表路径的尾斜杠变体——
 * Spring Boot 3 默认不宽容尾斜杠，必须显式映射）。</p>
 *
 * <p>权限模型：登录即持有自己的渠道（归属即权限，越权 404），不设模块权限码——
 * 渠道是「用户把自己的群接入告警」，不是平台资源。</p>
 */
@RestController
@RequestMapping("/api/channels")
public class ChannelController {

    private final ChannelService service;
    private final SessionAccessGuard accessGuard;

    public ChannelController(ChannelService service, SessionAccessGuard accessGuard) {
        this.service = service;
        this.accessGuard = accessGuard;
    }

    /** 渠道类型元数据（新建表单按 credentials_schema 动态渲染）。 */
    @GetMapping("/types")
    public List<Map<String, Object>> types(HttpServletRequest request) {
        accessGuard.requireUserId(request);
        return service.listTypes();
    }

    /** 渠道列表（尾斜杠 + 无斜杠双映射）。 */
    @GetMapping({"", "/"})
    public List<Map<String, Object>> list(HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        return service.list(userId).stream()
                .map(service::toRecord)
                .collect(Collectors.toList());
    }

    @PostMapping({"", "/"})
    public Map<String, Object> create(@RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        return service.toRecord(service.create(userId, body));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable("id") String id,
                                      @RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        return service.toRecord(service.update(userId, id, body));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id,
                                      HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        service.delete(userId, id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", true);
        return body;
    }

    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@PathVariable("id") String id,
                                      HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        return service.toRecord(service.setEnabled(userId, id, true));
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable("id") String id,
                                       HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        return service.toRecord(service.setEnabled(userId, id, false));
    }

    /** 连通状态（出站型渠道由最近一次投递结果推导）。 */
    @GetMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable("id") String id,
                                      HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        AlertChannel channel = service.requireOwned(userId, id);
        return service.toStatus(channel);
    }

    /** 发送测试消息（保存后验证连通性；结果同步返回并落渠道状态）。 */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable("id") String id,
                                    HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        return service.sendTest(userId, id);
    }
}
