package com.tipdm.agentplatform.service.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.domain.AlertChannel;
import com.tipdm.agentplatform.domain.ChannelCredential;
import com.tipdm.agentplatform.domain.PlatformNotification;
import com.tipdm.agentplatform.repository.AlertChannelRepository;
import com.tipdm.agentplatform.repository.ChannelCredentialRepository;
import com.tipdm.agentplatform.service.ExternalApiKeyCipher;
import com.tipdm.agentplatform.util.OutboundUrlGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警渠道扇出：payload 形态、加签、级别过滤与结果落库。
 *
 * <p>钉钉/飞书的加签算法是「第三方拿到 webhook + secret 才能收到消息」的
 * 安全边界，签名错了用户侧只会看到 400 sign 不匹配，非常难排查——所以
 * 这里用标准 JCE 复算一遍做双向验证，而不是只断言「签了」。</p>
 */
class AlertChannelDispatcherTest {

    private AlertChannelRepository repository;
    private ChannelCredentialRepository credentialRepository;
    private AlertChannelDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        repository = mock(AlertChannelRepository.class);
        credentialRepository = mock(ChannelCredentialRepository.class);
        // 出网守卫用真实实例：本测试里的 127.0.0.1 / 非法 URL 本就该被拦，
        // 走真实规则才能覆盖「地址未通过守卫 → 落 failed 状态」这条链路
        // （用 no-op mock 会把 SSRF 校验整段跳过，测试反而变松）。
        OutboundUrlGuard urlGuard = new OutboundUrlGuard(false);
        // 凭据解密：测试里的凭据是明文，令 decrypt 抛异常走 dispatcher 已有的
        // 「plaintext, using as-is」回退，不必引入密钥配置。
        ExternalApiKeyCipher cipher = mock(ExternalApiKeyCipher.class);
        when(cipher.decrypt(any())).thenThrow(new IllegalStateException("plaintext in test"));
        dispatcher = new AlertChannelDispatcher(
                repository, credentialRepository, new ChannelTypeRegistry(), new ObjectMapper(),
                urlGuard, cipher);
    }

    private PlatformNotification alert(String level) {
        PlatformNotification n = new PlatformNotification();
        n.setId(UUID.randomUUID());
        n.setUserId("u1");
        n.setLevel(level);
        n.setTitle("会话已空闲 40 分钟");
        n.setBody("详情正文");
        n.setSessionId("s1");
        return n;
    }

    private AlertChannel channel(String type, Map<String, String> credentials, String minLevel) {
        AlertChannel c = new AlertChannel();
        c.setId(UUID.randomUUID());
        c.setUserId("u1");
        c.setChannelType(type);
        c.setMinLevel(minLevel);
        c.setEnabled(true);
        if (credentials != null && !credentials.isEmpty()) {
            when(credentialRepository.findByChannelIdIn(any())).thenReturn(
                    credentials.entrySet().stream().map(e -> {
                        ChannelCredential row = new ChannelCredential();
                        row.setChannelId(c.getId());
                        row.setCredKey(e.getKey());
                        row.setCredValue(e.getValue());
                        return row;
                    }).toList());
        }
        return c;
    }

    /** 无启用渠道 → 0 条外发，不抛异常。 */
    @Test
    void dispatchWithNoChannelsIsNoop() {
        when(repository.findByUserIdAndEnabledTrue("u1")).thenReturn(List.of());
        assertThat(dispatcher.dispatch(alert("error"))).isZero();
    }

    /** min_level 过滤：info 告警不进 warn 门槛渠道；error 全过。 */
    @Test
    void dispatchRespectsPerChannelMinLevel() {
        // 渠道构造要在 repository 桩之前完成：channel() 内部会去桩 credentialRepository，
        // 嵌在 when(...).thenReturn(List.of(channel(...))) 里会触发 UnfinishedStubbing
        AlertChannel dingtalk = channel(ChannelTypeRegistry.TYPE_DINGTALK,
                Map.of("webhook_url", "https://oapi.example/robot"), "warn");
        when(repository.findByUserIdAndEnabledTrue("u1")).thenReturn(List.of(dingtalk));
        // info 低于 warn 门槛 → 不外发
        assertThat(dispatcher.dispatch(alert("info"))).isZero();
        // error 高于门槛 → 外发 1 条
        assertThat(dispatcher.dispatch(alert("error"))).isEqualTo(1);
    }

    /** 钉钉加签：sign = base64(HmacSHA256(key=secret, data="timestamp\nsecret"))，双向复算。 */
    @Test
    void dingtalkSignMatchesReferenceImplementation() throws Exception {
        String secret = "SEC1234567890";
        long timestamp = 1726452000000L;
        String sign = AlertChannelDispatcher.dingtalkSign(secret, timestamp);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(
                mac.doFinal((timestamp + "\n" + secret).getBytes("UTF-8")));
        assertThat(sign).isEqualTo(expected);
    }

    /** 飞书签名：sign = base64(HmacSHA256(key="timestamp\nsecret", data=""))，双向复算。 */
    @Test
    void feishuSignMatchesReferenceImplementation() throws Exception {
        String secret = "feishu-secret";
        long timestamp = 1726452000L;
        String sign = AlertChannelDispatcher.feishuSign(secret, timestamp);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec((timestamp + "\n" + secret).getBytes("UTF-8"), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        assertThat(sign).isEqualTo(expected);
    }

    /** 测试发送失败要落渠道状态（failed + last_error），成功则 connected。 */
    @Test
    void sendTestRecordsResultOnChannel() {
        AlertChannel ch = channel(ChannelTypeRegistry.TYPE_WEBHOOK,
                Map.of("url", "http://127.0.0.1:1/nope"), "warn");
        when(repository.findById(ch.getId())).thenReturn(Optional.of(ch));

        Map<String, Object> result = dispatcher.sendTest(ch);

        // 端口 1 不可达：必须失败且错误落库
        assertThat(result.get("ok")).isEqualTo(false);
        ArgumentCaptor<AlertChannel> captor = ArgumentCaptor.forClass(AlertChannel.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("failed");
        assertThat(captor.getValue().getLastError()).isNotBlank();
        assertThat(captor.getValue().getLastSentAt()).isNotNull();
    }

    /** 缺必填凭据的渠道：构造请求直接判失败，异常不得逃逸出 dispatch。 */
    @Test
    void dispatchWithInvalidUrlDoesNotThrow() {
        AlertChannel bad = channel(ChannelTypeRegistry.TYPE_DINGTALK,
                Map.of("webhook_url", "::not-a-url::"), "warn");
        when(repository.findByUserIdAndEnabledTrue("u1")).thenReturn(List.of(bad));
        when(repository.findById(bad.getId())).thenReturn(Optional.of(bad));

        assertThat(dispatcher.dispatch(alert("error"))).isEqualTo(1);
        ArgumentCaptor<AlertChannel> captor = ArgumentCaptor.forClass(AlertChannel.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("failed");
    }
}
