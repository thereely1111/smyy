package com.tipdm.agentplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 平台外部凭据的 AES-256-GCM 加解密。
 *
 * <p>实际服务**两块**数据（比类名暗示的「外部 API Key」宽一圈，改名会牵动 4 个消费方，故保留类名、
 * 在此写明职责）：</p>
 * <ul>
 *   <li>外部知识库（RAG）API Key —— {@code knowledge_bases.external_api_key_enc}</li>
 *   <li>告警渠道凭据集合 —— {@code channel_credentials.cred_value}，
 *       加密的是**整个键值集合的每个 value**：企微/钉钉/飞书 webhook 地址与加签 secret、Bearer token。
 *       即该密钥泄露 = 可以伪造平台往客户群里发告警。</li>
 * </ul>
 *
 * <p><b>密钥来源唯一</b>：{@code agent-platform.external.key}（env {@code AGENT_PLATFORM_EXTERNAL_KEY}），
 * 值为 base64 编码的 32 字节密钥，生成方式 {@code openssl rand -base64 32}。
 * <b>未配置、非 base64、或解码后不是 32 字节 → 启动失败</b>。</p>
 *
 * <p>历史实现缺配置时会回退到源码里可推导的固定开发密钥（{@code sha256("agent-platform-dev-external-key")}）。
 * 该回退已删除：它让「加密存储」在未配置的环境里变成用公开密钥即可解开的密文——攻击者读到库即可解密，
 * 「加密」只剩形式意义，而且**完全没有可见信号**（只打一条 WARN 就被忽略）。现改为 fail-fast，
 * 配置缺失在启动期暴露，不进入线上。</p>
 *
 * <p>⚠️ <b>轮换密钥的注意事项</b>：换钥会使存量密文全部无法解密，且两处消费方的失败行为**不对称**——</p>
 * <ul>
 *   <li>知识库侧 {@code ExternalRagClient} 未捕获异常 → 抛 500，问题可见;</li>
 *   <li>渠道侧 {@code AlertChannelDispatcher#decodeCredential} 把解密失败判定为「存量明文」原样返回
 *       （仅 debug 日志）→ 会拿密文当 secret 去加签，表现为**静默投递失败**，极难排查。</li>
 * </ul>
 * <p>因此轮换须按「先配过渡值让存量可解 → 逐条重存（渠道保存即整体覆盖转新钥；知识库需重新填 API Key，
 * 因为 {@code KnowledgeBaseService} 仅在显式传入密钥时才重加密）→ 再切新钥」进行。</p>
 */
@Service
public class ExternalApiKeyCipher {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiKeyCipher.class);
    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_LEN = 128;
    /** AES-256：密钥必须恰为 32 字节。 */
    private static final int KEY_LEN = 32;
    private static final String KEY_PROPERTY = "agent-platform.external.key";
    private static final String KEY_ENV = "AGENT_PLATFORM_EXTERNAL_KEY";

    private final SecretKey key;

    public ExternalApiKeyCipher(
            @Value("${agent-platform.external.key:}") String keyB64) {
        this.key = loadKey(keyB64);
        log.info("外部凭据加密器已就绪（AES-256-GCM，密钥来源 {}，{} 字节）", KEY_PROPERTY, KEY_LEN);
    }

    /**
     * 加载并校验密钥；任何不合规形态都抛 {@link IllegalStateException} 让应用**启动失败**。
     *
     * <p>为什么在构造期硬校验长度：{@code new SecretKeySpec(非32字节, "AES")} 本身不报错，
     * 要到首次加解密才抛 {@code InvalidKeyException}——启动日志干干净净，故障延迟到用户点「保存渠道」
     * 才出现。最常见的踩坑是**误填成 64 位十六进制字符串**（它是合法 base64，会解码成 48 字节），
     * 长度校验把它变成一条明确的启动期报错。</p>
     */
    private static SecretKey loadKey(String keyB64) {
        if (keyB64 == null || keyB64.isBlank()) {
            throw new IllegalStateException(
                    "未配置外部凭据加密密钥 " + KEY_PROPERTY + "（env " + KEY_ENV + "）。"
                            + "该密钥用于加密存储知识库 API Key 与告警渠道凭据，缺失时拒绝启动。"
                            + "配置 base64 编码的 32 字节密钥，生成方式：openssl rand -base64 32");
        }
        byte[] raw;
        try {
            // 容忍环境变量里常见的首尾换行/空白（k8s Secret、docker --env-file 都容易带入）
            raw = Base64.getDecoder().decode(keyB64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "外部凭据加密密钥 " + KEY_ENV + " 不是合法的 base64。"
                            + "注意不要带引号、换行或空格（值内部），应为 base64 编码的 32 字节", e);
        }
        if (raw.length != KEY_LEN) {
            throw new IllegalStateException(
                    "外部凭据加密密钥 " + KEY_ENV + " 长度非法：base64 解码后为 " + raw.length
                            + " 字节，AES-256 要求恰为 " + KEY_LEN + " 字节。"
                            + "若填的是 64 位十六进制字符串，它会解码成 48 字节——需先转成原始 32 字节再 base64，"
                            + "或直接执行 openssl rand -base64 32 生成新钥");
        }
        return new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv).put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("加密外部凭据失败", e);
        }
    }

    public String decrypt(String enc) {
        if (enc == null) {
            return null;
        }
        try {
            byte[] data = Base64.getDecoder().decode(enc);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(data, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[data.length - IV_LEN];
            System.arraycopy(data, IV_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN, iv));
            byte[] pt = c.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密外部凭据失败", e);
        }
    }
}
