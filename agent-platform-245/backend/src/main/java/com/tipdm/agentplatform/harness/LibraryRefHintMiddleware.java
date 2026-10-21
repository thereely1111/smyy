package com.tipdm.agentplatform.harness;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import reactor.core.publisher.Flux;

/**
 * 资料库引用提示追加：把用户消息里的 {@code library://node/<id>} 汇总后，
 * 在消息<b>末尾</b>追加一段带标记的工具提示。
 *
 * <p><b>为什么必须有这个中间件</b>：引用注入对话时只有协议串（模型看得见
 * {@code library://node/xxx} 但看不到正文），而工具是否该调用、怎么调，全靠模型
 * 自主判断。仅在系统提示里写一句"遇到 library:// 就调 library_read"是不够的——
 * 引用可能出现在几十轮前的历史消息里，模型不会意识到"这个旧引用还需要回读"。
 * 本中间件把提示<b>写进用户消息本身</b>，与引用同处一个上下文窗口，
 * 模型必然看到。</p>
 *
 * <p>这也是与 {@code knowledge_search} 的关键差异：后者是<b>会话级、固定集合</b>，
 * 可在系统提示里全量列名；节点引用是<b>消息内嵌、每条不同</b>，只能随消息带。</p>
 *
 * <p><b>提示追加在末尾而非就地贴在引用后</b>：改写后的 msgs 会被框架落进
 * transcript，就地括号会与用户句子连成一段，在历史消息里以「用户说的话」的形态
 * 出现。末尾集中追加 + {@code [系统提示]} 前缀使其可辨、可被前端整段剥离。</p>
 *
 * <p><b>不改引用本身</b>：只追加提示，原串保留——
 * 前端 {@code parseRefsFromText} 靠它还原附件 chip，改掉会丢 UI 还原。</p>
 *
 * <p><b>顺序</b>：{@code order()=2}，与 {@link AttachmentInlineGuardMiddleware} 同层，
 * 均早于框架的 @path 展开（框架默认 1）。本中间件不与它冲突——
 * {@code library://} 不匹配 AT_PATH（{@code :} 不在其字符类内），已实测。</p>
 */
public class LibraryRefHintMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(LibraryRefHintMiddleware.class);

    /** 资料库引用协议前缀。 */
    public static final String REF_SCHEME = "library://node/";

    /**
     * 引用 token：{@code library://node/<uuid>}。
     * 末尾 UUID 用宽松字符类，容忍大小写与连字符。
     */
    private static final Pattern LIBRARY_REF =
            Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(REF_SCHEME) + "([0-9a-fA-F-]{36})");

    /**
     * 提示标记；同时充当幂等判据（已含此串即不再补）。
     *
     * <p>文案刻意写成「用 library_read 工具读取」——模型按工具名匹配比按自然语言
     * 理解更稳。</p>
     */
    private static final String HINT_MARK = "用 library_read 工具读取";

    @Override
    public int order() {
        return 2;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        List<Msg> msgs = input.msgs();
        List<Msg> rewritten = null;
        for (int i = 0; i < msgs.size(); i++) {
            Msg msg = msgs.get(i);
            if (msg.getRole() != MsgRole.USER) {
                continue;
            }
            String text = msg.getTextContent();
            if (text == null || !text.contains(REF_SCHEME)) {
                continue;
            }
            Msg hinted = hint(msg, text);
            if (hinted == null) {
                // 所有引用都已有提示（或无有效引用）→ 保持原样
                continue;
            }
            if (rewritten == null) {
                rewritten = new ArrayList<>(msgs);
            }
            rewritten.set(i, hinted);
        }
        if (rewritten == null) {
            return next.apply(input);
        }
        return next.apply(new AgentInput(rewritten));
    }

    /**
     * 为消息里的资料库引用补一段工具提示，**追加到消息末尾**。
     *
     * <p><b>为什么不就地贴在引用后面</b>：就地括号（{@code xxx（用 library_read…）}）
     * 会与用户的句子连成一句，改写后的 msgs 又会被框架落进 transcript，
     * 于是这段系统提示以「用户说的话」的形式出现在历史消息里——
     * 用户气泡里凭空多出一行工具调用说明。集中追加到末尾并加显式标记后，
     * 它一眼可辨是系统附加内容，前端也能整段剥离。</p>
     *
     * <p><b>引用串本身一个字都不改</b>：前端 {@code parseRefsFromText} 靠它还原
     * chip，改掉会丢 UI 还原。</p>
     *
     * <p><b>幂等</b>：按「本条消息是否已含标记前缀」整体判断，
     * 而不是逐个引用加判据——历史消息会被每轮 run 反复处理，
     * 逐引用判据在文本被用户手改后可能失效，导致提示无限追加。
     * 已含标记就整条跳过，代价是新增引用不会被补提示，
     * 这可接受（同一轮里首次处理时所有引用都会带上）。</p>
     *
     * @return 改写后的消息；无需改写返回 null
     */
    private Msg hint(Msg msg, String text) {
        if (text.contains(HINT_MARK)) {
            // 已补过提示（历史消息每轮都会再进这里）
            return null;
        }
        Matcher m = LIBRARY_REF.matcher(text);
        StringBuilder ids = new StringBuilder();
        int count = 0;
        while (m.find()) {
            if (count > 0) {
                ids.append(", ");
            }
            ids.append(m.group(1));
            count++;
        }
        if (count == 0) {
            return null;
        }
        String hinted = text
                + "\n\n[系统提示] "
                + HINT_MARK + "以下 nodeId 取内容：" + ids
                + "（内容是文档正文；若是文件夹则返回其条目清单，可用清单里的引用继续读取）。";
        log.debug("library ref hint applied to {} ref(s)", count);
        return msg.withContent(List.of(TextBlock.builder().text(hinted).build()));
    }
}
