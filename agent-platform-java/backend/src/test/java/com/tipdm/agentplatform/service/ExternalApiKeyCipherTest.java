package com.tipdm.agentplatform.service;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外部凭据加密器测试（守护 P1-7）。
 *
 * <p>背景：历史实现在 {@code AGENT_PLATFORM_EXTERNAL_KEY} 未配置时回退到源码里可推导的固定开发密钥
 * （{@code sha256("agent-platform-dev-external-key")}），且只打一条 WARN。后果是「加密存储」在未配置
 * 的环境里等价于用公开密钥加密——攻击者读到库即可解密，而线上**没有任何可见信号**。现改为
 * fail-fast：缺失/非法配置一律让应用启动失败。</p>
 *
 * <p>这些用例直接 {@code new} 真实类（不起 Spring 上下文），因此同时也是「改动真的落地」的证据——
 * 与前端核对 bundle、后端 {@code javap} 核字节码同属一类校验。</p>
 */
class ExternalApiKeyCipherTest {

    private static String randomKeyB64() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    /* ─────────────────────────── fail-fast：拒绝启动 ─────────────────────────── */

    @Test
    void rejectsMissingKey_failsFast() {
        // 空串 / null / 纯空白三种形态都必须拒绝（纯空白是 env 里最容易被忽略的形态）
        for (String missing : new String[]{"", null, "   "}) {
            assertThatThrownBy(() -> new ExternalApiKeyCipher(missing))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AGENT_PLATFORM_EXTERNAL_KEY")
                    .hasMessageContaining("拒绝启动");
        }
    }

    @Test
    void rejectsNonBase64Key_failsFast() {
        assertThatThrownBy(() -> new ExternalApiKeyCipher("not a base64 key!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是合法的 base64");
    }

    @Test
    void rejectsHexKey_withLengthDiagnosis() {
        // 真实踩坑形态：用户把密钥摘要的**十六进制**字符串直接填进环境变量。
        // 它是合法 base64（0-9a-f 全在字母表内），会解码成 48 字节 → 不做长度校验的话
        // 构造期不报错，直到首次加解密才抛 InvalidKeyException，启动日志干干净净。
        String hex = "d744533062a682c7fe0f642427062dabd566c31f5064a543c50bb411496c35c9";
        assertThatThrownBy(() -> new ExternalApiKeyCipher(hex))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("48")
                .hasMessageContaining("32");
    }

    @Test
    void rejectsWrongLengthKey_failsFast() {
        // AES-128 长度的密钥同样拒绝：本类按 AES-256 设计，长度不一致应当启动期暴露而非运行期
        String key16 = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new ExternalApiKeyCipher(key16))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16")
                .hasMessageContaining("32");

        String key64 = Base64.getEncoder().encodeToString(new byte[64]);
        assertThatThrownBy(() -> new ExternalApiKeyCipher(key64))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64");
    }

    @Test
    void rejectsLegacyDevKeyLiteral() {
        // 旧的回退字面量本身不能当环境变量用（含 '-'，非合法 base64）。
        // 要让存量密文可解，必须配它 sha256 摘要的 base64（见下个用例）。
        assertThatThrownBy(() -> new ExternalApiKeyCipher("agent-platform-dev-external-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是合法的 base64");
    }

    /* ─────────────────────────── 合法密钥：正常加解密 ─────────────────────────── */

    @Test
    void acceptsValidKey_andRoundTrips() {
        ExternalApiKeyCipher cipher = new ExternalApiKeyCipher(randomKeyB64());

        String secret = "sk-live-abc123-中文-!@#$%^&*()";
        String enc = cipher.encrypt(secret);

        assertThat(enc).isNotNull().isNotEqualTo(secret);
        assertThat(cipher.decrypt(enc)).isEqualTo(secret);
    }

    @Test
    void toleratesSurroundingWhitespace() {
        // k8s Secret / docker --env-file 容易带进首尾换行；这类不可控空白应被容忍，
        // 但不能容忍值内部空白（见 rejectsNonBase64Key_failsFast）
        String key = randomKeyB64();
        ExternalApiKeyCipher clean = new ExternalApiKeyCipher(key);
        ExternalApiKeyCipher padded = new ExternalApiKeyCipher("  " + key + "\n");

        // 两边是同一把钥：padding 实例能解开 clean 实例的密文（构造期不抛异常即已被本行隐含断言）
        assertThat(padded.decrypt(clean.encrypt("shared"))).isEqualTo("shared");
    }

    @Test
    void acceptsDevKeyDigestBase64_asLegacyTransitionValue() {
        // 过渡用法的回归守卫：把 dev 密钥摘要的 base64 配进环境变量，可解开用旧回退密钥加密的存量密文。
        // 值 = base64(sha256("agent-platform-dev-external-key"))，仅用于过渡，重存完凭据后须换真随机钥。
        String transition = "10RTMGKmgsf+D2QkJwYtq9Vmwx9QZKVDxQu0EUlsNck=";

        assertThatCode(() -> new ExternalApiKeyCipher(transition)).doesNotThrowAnyException();
        ExternalApiKeyCipher cipher = new ExternalApiKeyCipher(transition);
        assertThat(cipher.decrypt(cipher.encrypt("legacy"))).isEqualTo("legacy");
    }

    @Test
    void encryptUsesFreshIv_perCall() {
        // GCM 的 IV 绝不能复用：同明文两次加密的密文必须不同，且都能解回
        ExternalApiKeyCipher cipher = new ExternalApiKeyCipher(randomKeyB64());

        String first = cipher.encrypt("same-plaintext");
        String second = cipher.encrypt("same-plaintext");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("same-plaintext");
        assertThat(cipher.decrypt(second)).isEqualTo("same-plaintext");
    }

    @Test
    void nullPassthrough() {
        ExternalApiKeyCipher cipher = new ExternalApiKeyCipher(randomKeyB64());

        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void decryptWithDifferentKey_fails() {
        // 换钥后存量密文解不开时的行为：抛异常（知识库侧表现为 500，可见）。
        // ⚠️ 渠道侧 AlertChannelDispatcher#decodeCredential 会把该异常当作「存量明文」原样返回，
        // 表现为静默投递失败——本用例只锁住 cipher 自身的行为。
        ExternalApiKeyCipher a = new ExternalApiKeyCipher(randomKeyB64());
        ExternalApiKeyCipher b = new ExternalApiKeyCipher(randomKeyB64());

        String enc = a.encrypt("secret");
        assertThatThrownBy(() -> b.decrypt(enc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密外部凭据失败");
    }

    @Test
    void decryptTamperedCiphertext_fails() {
        // GCM 认证标签生效：篡改密文必须解密失败，而不是返回垃圾明文
        ExternalApiKeyCipher cipher = new ExternalApiKeyCipher(randomKeyB64());
        String enc = cipher.encrypt("secret");
        byte[] raw = Base64.getDecoder().decode(enc);
        raw[raw.length - 1] ^= 0x01; // 翻转最后 1 字节（落在 GCM tag 上）
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyBytesAreUsedVerbatim_notDerived() {
        // 配置路径不做任何派生（不像旧 devKey 那样先 sha256）：同一 base64 的两个实例密钥相同，
        // 且不同 base64 的实例互不可解。这锁住「env 值就是原始 32 字节的 base64」这一契约。
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String key = Base64.getEncoder().encodeToString(raw);

        ExternalApiKeyCipher one = new ExternalApiKeyCipher(key);
        ExternalApiKeyCipher two = new ExternalApiKeyCipher(key);
        ExternalApiKeyCipher other = new ExternalApiKeyCipher(randomKeyB64());

        String enc = one.encrypt("payload");
        assertThat(two.decrypt(enc)).isEqualTo("payload");
        assertThatThrownBy(() -> other.decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }
}
