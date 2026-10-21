package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.harness.SessionActivityTracker;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.SessionTranscriptResponse;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.SessionTurnsResponse;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 会话转录读取：把 AgentScope 的会话状态（{@code agent_state}）与归档 jsonl 审计
 * 合并成前端消费的转录形状。
 *
 * <p>内容与归档解析逻辑从 {@code AgentRuntimeService} 拆出，使会话元数据管理与
 * 转录构建各自独立、单文件体量可控。</p>
 */
@Service
public class SessionTranscriptService {

    private static final Logger log = LoggerFactory.getLogger(SessionTranscriptService.class);

    private final AgentAssemblyService assemblyService;
    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final SessionActivityTracker sessionActivityTracker;
    private final ObjectMapper objectMapper;

    public SessionTranscriptService(AgentAssemblyService assemblyService,
                                    SessionWorkspaceManager sessionWorkspaceManager,
                                    SessionActivityTracker sessionActivityTracker,
                                    ObjectMapper objectMapper) {
        this.assemblyService = assemblyService;
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.sessionActivityTracker = sessionActivityTracker;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回某会话的持久化转录（消息上下文），供前端增量同步后台唤醒续跑产生的段落。
     *
     * <p>会话按 (userId, sessionId) 分区读取，与 {@link AgentRuntimeService#listSessions} 使用同一
     * state store key（agent_state）；会话不存在时返回空列表。</p>
     *
     * <p><b>Compaction 解耦</b>：state.context 在框架 Compaction 触发后会塌缩为
     * {@code [摘要消息] + 保留尾部}，早期对话从模型上下文中消失——若直接返回它，
     * 用户在界面上就会"失去"压缩前的历史（2026-09-12 教材会话实测）。检测到压缩
     * 摘要消息时，改为「jsonl 会话审计（框架每轮全量追加）重建完整历史 + state 中
     * 压缩后新增的消息」按消息 id 去重拼接；审计文件缺失/解析失败则回退原实现。</p>
     *
     * <p><b>分批加载（2026-09-30）</b>：全量响应在超长会话（转录可达十几 MB）下
     * 每次轮询都让前端 JSON.parse + 合并整棵消息树，浏览器内存与主线程双双被打爆。
     * 增加三个可选游标参数：</p>
     * <ul>
     *   <li>{@code after=<msgId>}：只返回该 id 之后的消息（增量轮询路径）；
     *       id 找不到（压缩重写历史等）时返回 {@code stale=true}，客户端重新全量恢复。</li>
     *   <li>{@code limit=N}：尾部 N 条（打开会话只拉尾部，向上滚动再翻页）。</li>
     *   <li>{@code before=<msgId>}（与 limit 连用）：该 id 之前的 N 条（向上翻页）；
     *       id 找不到时回退为尾部 N 条并置 {@code stale=true}。</li>
     *   <li>响应新增 {@code total}（服务端全量条数）；无参数时行为与旧版一致（全量）。</li>
     * </ul>
     *
     * <p><b>after 热路径零成本</b>：轮询的常态是「无新消息」。把全量列表的
     * (指纹, total, firstId, lastId) 记入 {@link #transcriptHeadCache}，指纹未变且
     * 游标正好等于 lastId 时直接返回空增量——跳过 Msg→块转换与归档 jsonl 合并
     * （超长会话这两步是每轮轮询的服务端 CPU 大头）。</p>
     */
    public SessionTranscriptResponse transcript(AgentConfig config, String userId, String sessionId,
                                                Integer limit, String afterId, String beforeId) {
        HarnessAgent agent = assemblyService.requireAssembled(config);
        List<Msg> context = agent.getStateStore()
                .get(userId, sessionId, "agent_state", AgentState.class)
                .map(AgentState::getContext)
                .orElse(List.of());
        if (context == null) {
            context = List.of();
        }
        String fingerprint = contextFingerprint(context);
        String cacheKey = userId + "|" + sessionId;

        // after 热路径：无新消息时零转换直接返回（见方法 javadoc）。
        if (afterId != null && !afterId.isBlank()) {
            TranscriptHead head;
            synchronized (transcriptHeadCache) {
                head = transcriptHeadCache.get(cacheKey);
            }
            if (head != null && head.fingerprint().equals(fingerprint)
                    && afterId.equals(head.lastId())) {
                return new SessionTranscriptResponse(sessionId, List.of(), head.total(), false,
                        sessionActivityTracker.isRunning(sessionId));
            }
        }

        boolean compacted = context.stream()
                .anyMatch(m -> m.getId() != null
                        && m.getId().startsWith(COMPACTION_SUMMARY_ID_PREFIX));
        // 转录转换缓存（2026-09-17）：前端 transcriptSync 轮询本接口，长会话每次
        // 都要整趟「Msg→块转换 + 路径脱敏 + tool_result 归位」，纯 CPU 重复劳动。
        // 结构指纹未变（无追加/压缩/后台工具结果回填）时直接复用上次转换结果。
        StateTranscriptCache cached;
        synchronized (stateTranscriptCache) {
            cached = stateTranscriptCache.get(cacheKey);
        }
        List<SessionTranscriptResponse.Message> messages;
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            messages = cached.messages();
        } else {
            // thinking 时长：与上下文中前一条消息的时间差 ≈ 该轮思考+生成耗时（秒）。
            messages = new ArrayList<>(context.size());
            long prevEpochMillis = 0L;
            for (Msg msg : context) {
                long cur = parseEpochMillis(msg.getTimestamp());
                messages.add(toTranscriptMessage(msg, cur - prevEpochMillis));
                prevEpochMillis = cur > 0 ? cur : prevEpochMillis;
            }
            // tool_result 归位：state.context 里 tool result 是独立 role=tool 消息，
            // 前端按单消息配对 tool_call↔tool_result——不归位则恢复的历史里已完成的
            // 工具永远显示 spinner（2026-09-12 实测）。合并进 assistant 消息后与实时流一致。
            messages = foldToolResultsIntoAssistant(messages);
            // 缓存只驻留常规体量（上限见 STATE_TRANSCRIPT_CACHE_MAX_ENTRY_CHARS），
            // 超长转录按次转换、不驻留，避免常驻堆被少数巨型会话钉死。
            long approxChars = 0L;
            for (SessionTranscriptResponse.Message m : messages) {
                for (Map<String, Object> block : m.content()) {
                    for (Object v : block.values()) {
                        if (v instanceof String s) {
                            approxChars += s.length();
                        }
                    }
                }
            }
            if (approxChars <= STATE_TRANSCRIPT_CACHE_MAX_ENTRY_CHARS) {
                synchronized (stateTranscriptCache) {
                    stateTranscriptCache.put(cacheKey,
                            new StateTranscriptCache(fingerprint, messages));
                }
            } else if (oversizedStateTranscripts.add(cacheKey)) {
                log.warn("State transcript {} for session {} is ~{} chars — converted per "
                                + "request but not cached (over the {} char cache-entry limit)",
                        userId, sessionId, approxChars, STATE_TRANSCRIPT_CACHE_MAX_ENTRY_CHARS);
            }
        }
        if (compacted) {
            messages = mergeWithArchivedTranscript(config, sessionId, messages);
        }

        // 记录全量列表头信息（after 热路径判定用）。压缩合并后的列表才是前端
        // 看到的全量，必须以它为准。
        int total = messages.size();
        synchronized (transcriptHeadCache) {
            if (transcriptHeadCache.size() >= TRANSCRIPT_HEAD_CACHE_MAX_ENTRIES
                    && !transcriptHeadCache.containsKey(cacheKey)) {
                transcriptHeadCache.clear();
            }
            transcriptHeadCache.put(cacheKey, new TranscriptHead(
                    fingerprint, total,
                    messages.isEmpty() ? null : messages.get(0).id(),
                    messages.isEmpty() ? null : messages.get(messages.size() - 1).id()));
        }

        List<SessionTranscriptResponse.Message> out = messages;
        boolean stale = false;
        int cappedLimit = limit == null ? -1 : Math.min(Math.max(limit, 0), TRANSCRIPT_MAX_LIMIT);
        if (afterId != null && !afterId.isBlank()) {
            int idx = indexOfMessageId(messages, afterId);
            if (idx < 0) {
                // 游标失效：历史被压缩重写 / 服务端状态变更。列表为空（会话已清）
                // 时不算 stale——客户端无事可做；非空才要求客户端全量重恢复。
                out = List.of();
                stale = !messages.isEmpty();
            } else {
                out = messages.subList(idx + 1, messages.size());
            }
        } else if (cappedLimit >= 0) {
            int end;
            if (beforeId != null && !beforeId.isBlank()) {
                int idx = indexOfMessageId(messages, beforeId);
                if (idx < 0) {
                    // 翻页游标失效（历史被压缩重写）：回退为尾部 limit 条，
                    // stale=true 让客户端重置「更早」游标。
                    end = messages.size();
                    stale = !messages.isEmpty();
                } else {
                    end = idx;
                }
            } else {
                end = messages.size();
            }
            int from = Math.max(0, end - cappedLimit);
            out = messages.subList(from, end);
        }
        return new SessionTranscriptResponse(sessionId, out, total, stale,
                sessionActivityTracker.isRunning(sessionId));
    }

    /**
     * 会话「用户轮次索引」：对话区左侧导航条（minimap）的锚点。
     *
     * <p>锚点必须覆盖<b>整个会话</b>。转录分批加载后前端只有尾部若干条消息，锚点若
     * 由前端从已加载消息里推导，长会话的可用轮次会随加载窗口一起缩水——少到不足
     * 两个时整条导航条直接不显示。故锚点单独下发，只回 id 与两端摘要（几十条轮次
     * 也只有几 KB），与加载窗口解耦。</p>
     *
     * <p>实现上复用 {@link #transcript} 的全量列表而非另起一套解析：锚点 id 必须与
     * 前端 {@code data-message-id} 同源——tool_result 归位、压缩归档合并都会改写
     * id，另走一条读取路径必然对不上，点击就定位不到。代价是与一次全量转录等量的
     * 列表构建（受 stateTranscriptCache 与归档缓存吸收）；调用频率按「打开会话 +
     * 新增用户轮次」计，不参与轮询。</p>
     */
    public SessionTurnsResponse turns(AgentConfig config, String userId, String sessionId) {
        List<SessionTranscriptResponse.Message> all =
                transcript(config, userId, sessionId, null, null, null).messages();
        List<SessionTurnsResponse.Turn> turns = new ArrayList<>();
        for (int i = 0; i < all.size() && turns.size() < MAX_TURN_ANCHORS; i++) {
            SessionTranscriptResponse.Message m = all.get(i);
            if (!"user".equals(m.role())) {
                continue;
            }
            String text = transcriptPlainText(m).trim();
            if (!isTurnAnchor(m, text)) {
                continue;
            }
            turns.add(new SessionTurnsResponse.Turn(m.id(), clipForAnchor(text),
                    clipForAnchor(firstAssistantText(all, i + 1))));
        }
        return new SessionTurnsResponse(sessionId, turns);
    }

    /**
     * 转录消息的正文拼接：只取 text 块、以换行相接（与前端 transcriptText 同口径）。
     */
    private static String transcriptPlainText(SessionTranscriptResponse.Message m) {
        if (m.content() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : m.content()) {
            if (!"text".equals(block.get("type"))) {
                continue;
            }
            if (block.get("text") instanceof String text && !text.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    /**
     * 该 user 消息是否构成一个导航锚点。
     *
     * <p>平台注入的 user 消息（异步任务完成通知 / 停滞自检 / HITL 恢复 / 压缩摘要 /
     * 带 synthetic 标记的补发）不是用户发言，前端既不渲染成气泡也不计入轮次，
     * 导航条同样跳过；{@code [} 前缀的「平台留痕」按同一口径排除。判据与前端
     * transcriptSync 的跳过集合一致，两处要一起改。</p>
     */
    private static boolean isTurnAnchor(SessionTranscriptResponse.Message m, String text) {
        if (text.isEmpty() || text.startsWith("[") || text.startsWith("（用户")) {
            return false;
        }
        if (m.id() != null && m.id().startsWith(COMPACTION_SUMMARY_ID_PREFIX)) {
            return false;
        }
        return m.metadata() == null || !Boolean.TRUE.equals(m.metadata().get("synthetic"));
    }

    /** 该轮次之后的第一条助手正文（未截断）；到下一个用户轮次或列表结束为止。 */
    private static String firstAssistantText(List<SessionTranscriptResponse.Message> all, int from) {
        for (int i = from; i < all.size(); i++) {
            SessionTranscriptResponse.Message m = all.get(i);
            if ("user".equals(m.role())) {
                break;
            }
            if (!"assistant".equals(m.role())) {
                continue;
            }
            String text = transcriptPlainText(m).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    /** 锚点摘要截断（只用于悬停提示）。 */
    private static String clipForAnchor(String text) {
        return text.length() <= TURN_ANCHOR_TEXT_MAX_CHARS
                ? text : text.substring(0, TURN_ANCHOR_TEXT_MAX_CHARS);
    }

    /** 在转录消息列表中按 id 定位下标；未命中返回 -1。 */
    private static int indexOfMessageId(List<SessionTranscriptResponse.Message> messages,
                                        String id) {
        for (int i = 0; i < messages.size(); i++) {
            if (id.equals(messages.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * state 上下文的结构指纹：消息数 + 末条 id + 逐消息校验和
     * （id/时间戳/块数/块类型/文本长度/工具状态序数）。
     *
     * <p>会话追加、Compaction 塌缩、后台工具结果回填（RUNNING→终态、占位文本→
     * 真实输出）至少会改变其中一项；指纹一致则认为转录未变化。只读 Msg 结构、
     * 不做字符串展开，一趟 O(N) 且远轻于块转换 + 路径脱敏。</p>
     */
    private static String contextFingerprint(List<Msg> context) {
        long acc = 17L;
        for (Msg m : context) {
            acc = acc * 31 + String.valueOf(m.getId()).hashCode();
            acc = acc * 31 + String.valueOf(m.getTimestamp()).hashCode();
            List<ContentBlock> content = m.getContent();
            acc = acc * 31 + (content == null ? 0 : content.size());
            if (content == null) {
                continue;
            }
            for (ContentBlock b : content) {
                acc = acc * 31 + b.getClass().getSimpleName().hashCode();
                if (b instanceof TextBlock t) {
                    acc = acc * 31 + (t.getText() == null ? 0 : t.getText().length());
                } else if (b instanceof ThinkingBlock t) {
                    acc = acc * 31 + (t.getThinking() == null ? 0 : t.getThinking().length());
                } else if (b instanceof ToolUseBlock t) {
                    acc = acc * 31 + (t.getState() == null ? 0 : t.getState().ordinal());
                } else if (b instanceof ToolResultBlock t) {
                    acc = acc * 31 + (t.getState() == null ? 0 : t.getState().ordinal());
                    acc = acc * 31 + (t.getOutput() == null ? 0 : t.getOutput().size());
                }
            }
        }
        Msg last = context.isEmpty() ? null : context.get(context.size() - 1);
        return context.size() + ":" + (last == null ? "-" : String.valueOf(last.getId())) + ":" + acc;
    }

    /** 单条 state 转录缓存项的字符量上限（≈8MB 堆/条）；超过则按次转换不驻留。 */
    private static final long STATE_TRANSCRIPT_CACHE_MAX_ENTRY_CHARS = 4_000_000L;

    private static final int STATE_TRANSCRIPT_CACHE_MAX_ENTRIES = 8;

    /** 分页单次最大条数（防误用打爆服务端）。 */
    private static final int TRANSCRIPT_MAX_LIMIT = 2_000;

    /** 轮次索引单次最多返回的锚点数（超出部分丢弃，导航条本就只用尾部）。 */
    private static final int MAX_TURN_ANCHORS = 500;

    /** 轮次锚点摘要的字符上限：只喂悬停提示，不承载完整正文。 */
    private static final int TURN_ANCHOR_TEXT_MAX_CHARS = 600;

    /** 转录头信息缓存上限（键 = user|session，条目极小；超限整体清空重建）。 */
    private static final int TRANSCRIPT_HEAD_CACHE_MAX_ENTRIES = 2_000;

    /**
     * 全量转录列表的轻量头信息（指纹 + 条数 + 首尾 id）。
     *
     * <p>与 {@link StateTranscriptCache} 的区别：不驻留消息体，超长会话也记录——
     * after 增量轮询的「无新消息」热路径靠它零成本短路。</p>
     */
    private record TranscriptHead(String fingerprint, int total, String firstId, String lastId) { }

    private final Map<String, TranscriptHead> transcriptHeadCache =
            new HashMap<>();

    private record StateTranscriptCache(String fingerprint,
                                        List<SessionTranscriptResponse.Message> messages) { }

    private final Map<String, StateTranscriptCache> stateTranscriptCache =
            new LinkedHashMap<>(STATE_TRANSCRIPT_CACHE_MAX_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, StateTranscriptCache> eldest) {
                    return size() > STATE_TRANSCRIPT_CACHE_MAX_ENTRIES;
                }
            };

    /** 超过体量上限、被排除在 state 转录缓存外的会话（只告警一次）。 */
    private final Set<String> oversizedStateTranscripts =
            ConcurrentHashMap.newKeySet();

    /**
     * 解析消息时间戳为 epoch 秒。
     *
     * <p>框架 {@code Msg.getTimestamp()} 的规范格式是 {@code "yyyy-MM-dd HH:mm:ss.SSS"}
     * （本地时区、无 T/Z，见 Msg.TIMESTAMP_FORMATTER）；转录输出走 ISO 的地方另行处理。
     * 2026-09-12 实测：此前只支持 ISO，导致 thinking 时长恒为 0。</p>
     */
    private static long parseEpochMillis(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (Exception ignored) {
            // fallthrough
        }
        try {
            return LocalDateTime.parse(timestamp.replace(' ', 'T'))
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /**
     * 把 role=tool 消息里的 tool_result 块移进持有同名 id tool_call 块的 assistant
     * 消息 content 末尾（块 id 即 toolCallId）；搬空的原消息丢弃。
     */
    private List<SessionTranscriptResponse.Message> foldToolResultsIntoAssistant(
            List<SessionTranscriptResponse.Message> messages) {
        Map<String, SessionTranscriptResponse.Message> assistantByResultId = new HashMap<>();
        for (SessionTranscriptResponse.Message m : messages) {
            if (!"assistant".equals(m.role())) {
                continue;
            }
            for (Map<String, Object> block : m.content()) {
                if ("tool_call".equals(block.get("type")) && block.get("id") != null) {
                    assistantByResultId.put(String.valueOf(block.get("id")), m);
                }
            }
        }
        List<SessionTranscriptResponse.Message> out = new ArrayList<>(messages.size());
        for (SessionTranscriptResponse.Message m : messages) {
            if ("tool".equals(m.role())) {
                List<Map<String, Object>> results = m.content().stream()
                        .filter(b -> "tool_result".equals(b.get("type")))
                        .toList();
                if (!results.isEmpty() && results.size() == m.content().size()) {
                    boolean allFolded = true;
                    for (Map<String, Object> result : results) {
                        SessionTranscriptResponse.Message owner =
                                assistantByResultId.get(String.valueOf(result.get("id")));
                        if (owner != null) {
                            owner.content().add(result);
                        } else {
                            allFolded = false;
                        }
                    }
                    if (allFolded) {
                        continue; // 整条 tool 消息已被搬空，丢弃
                    }
                    // 部分折叠：保留剩余（非 tool_result）块
                    List<Map<String, Object>> remaining = m.content().stream()
                            .filter(b -> !"tool_result".equals(b.get("type"))
                                    || assistantByResultId.get(String.valueOf(b.get("id"))) == null)
                            .toList();
                    out.add(new SessionTranscriptResponse.Message(
                            m.id(), m.role(), m.name(), m.createdAt(), remaining, m.metadata()));
                    continue;
                }
            }
            out.add(m);
        }
        return out;
    }

    /** Compaction 摘要消息的 id 前缀（框架 ConversationCompactor.buildSummaryMessage）。 */
    private static final String COMPACTION_SUMMARY_ID_PREFIX = "__compaction_summary__:";

    /**
     * 归档 jsonl 的可续解析状态。归档文件是 append-only：缓存不存解析结果快照，
     * 而是存解析续态（各归组索引 + 已消费字节 offset），下次请求只解析追加部分。
     * 「疑似平铺思考」候选不在此处清算——清算会改写消息对象，快照构建时以副本形式
     * 落地，保持续态不被污染（见 {@link #buildArchiveSnapshot}）。
     */
    private static final class ArchivedParseState {
        private final Map<String, SessionTranscriptResponse.Message> messageByEntryId = new HashMap<>();
        private final Map<String, SessionTranscriptResponse.Message> legacyThinkingMessages = new HashMap<>();
        private final Map<String, PendingTool> toolUseByEntryId = new HashMap<>();
        private final List<SessionTranscriptResponse.Message> ordered = new ArrayList<>();
        private double prevTs = 0d;
        private long approxChars = 0L;
        /** 已消费的字节 offset（指向最后一条完整行的行尾换行之后）；从该位置续读。 */
        private long consumedBytes = 0L;
        /** append-only 守卫：文件首行，续解析前比对，不一致（文件被重写）则全量重解析。 */
        private String firstLine;
    }

    private record PendingTool(String toolCallId, SessionTranscriptResponse.Message owner) { }

    /**
     * jsonl 增量解析缓存：前端 transcriptSync 轮询本接口，避免反复读盘解析。
     *
     * <p>值是 {@link ArchivedParseState} 续态：文件只追加时按 offset 续解析新行，
     * 活跃 run 期间归档持续追加也不再触发整文件重解析。有界：最多
     * {@link #TRANSCRIPT_CACHE_MAX_ENTRIES} 条；单条解析后字符串量超过
     * {@link #TRANSCRIPT_CACHE_MAX_ENTRY_CHARS} 的文件不驻留（回退每请求全量
     * 解析）；所有缓存条目的字符总量超过 {@link #TRANSCRIPT_CACHE_TOTAL_CHARS}
     * 时从最旧条目开始淘汰。</p>
     */
    private static final int TRANSCRIPT_CACHE_MAX_ENTRIES = 16;

    /**
     * 单条缓存项（解析后字符串量）上限；超过则不驻留。
     * 上限须覆盖平台实际出现的最大会话归档（当前最大 ≈1060 万字符/16MB 文件）：
     * 超限会退化为「每次请求全量重读重解析」，而归档在活跃 run 期间持续追加，
     * transcriptSync 轮询会让该成本反复发生。
     */
    private static final long TRANSCRIPT_CACHE_MAX_ENTRY_CHARS = 32_000_000L;

    /** 全部缓存条目的总字符量预算；超限从最旧条目淘汰。 */
    private static final long TRANSCRIPT_CACHE_TOTAL_CHARS = 64_000_000L;

    private final Map<Path, ArchivedParseState> archivedTranscriptCache =
            new LinkedHashMap<>(TRANSCRIPT_CACHE_MAX_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Path, ArchivedParseState> eldest) {
                    return size() > TRANSCRIPT_CACHE_MAX_ENTRIES;
                }
            };

    /** 超过体积上限、被排除在缓存外的文件（只告警一次，避免每 5s 刷日志）。 */
    private final Set<Path> oversizedTranscripts = ConcurrentHashMap.newKeySet();

    /**
     * 用 jsonl 会话审计重建完整历史，并拼上 state 中压缩后新增的消息。
     * 去重键 = 消息 id 与块 id（jsonl 尾部与 state 保留尾部必然重叠）。
     */
    private List<SessionTranscriptResponse.Message> mergeWithArchivedTranscript(
            AgentConfig config, String sessionId,
            List<SessionTranscriptResponse.Message> current) {
        Path archived = locateSessionTranscriptJsonl(config, sessionId);
        if (archived == null) {
            return current;
        }
        List<SessionTranscriptResponse.Message> archivedMsgs = parseArchivedTranscript(archived);
        if (archivedMsgs.isEmpty()) {
            return current;
        }
        // jsonl 里也会被写入压缩摘要消息行（SessionTranscriptWriter 不区分），
        // 与 state 侧同样过滤，避免伪用户气泡混进历史。
        archivedMsgs = archivedMsgs.stream()
                .filter(m -> m.id() == null
                        || !m.id().startsWith(COMPACTION_SUMMARY_ID_PREFIX))
                .toList();
        List<SessionTranscriptResponse.Message> merged = new ArrayList<>(archivedMsgs);
        Map<String, Integer> indexById = new HashMap<>();
        for (int i = 0; i < merged.size(); i++) {
            indexById.put(merged.get(i).id(), i);
        }
        for (SessionTranscriptResponse.Message m : current) {
            // 压缩摘要消息不进转录（前端也过滤，双保险）
            if (m.id() != null && m.id().startsWith(COMPACTION_SUMMARY_ID_PREFIX)) {
                continue;
            }
            Integer idx = m.id() == null ? null : indexById.get(m.id());
            if (idx != null) {
                // state 版优先：块结构更完整（thinking 独立成块、工具状态准确），
                // 覆盖 jsonl 版（思考拼进正文）
                merged.set(idx, m);
            } else {
                merged.add(m);
                if (m.id() != null) {
                    indexById.put(m.id(), merged.size() - 1);
                }
            }
        }
        log.info("Transcript merged with archive: archived={}, state={}, merged={}",
                archivedMsgs.size(), current.size(), merged.size());
        return merged;
    }

    /**
     * 定位框架 SessionTranscriptWriter 写的 jsonl 会话审计。
     * 实测路径：{@code runtimes/{agentId}/{sessionId}/agents/{agentName}/sessions/{sessionId}.jsonl}
     * （agentName 因 agent 类型而异，按目录扫描不假设名字）。
     */
    private Path locateSessionTranscriptJsonl(AgentConfig config, String sessionId) {
        try {
            Path sessionDir = sessionWorkspaceManager.resolveSessionDir(
                    config.getId().toString(), sessionId);
            Path agentsDir = sessionDir.resolve("agents");
            if (!Files.isDirectory(agentsDir)) {
                return null;
            }
            try (Stream<Path> stream = Files.walk(agentsDir, 3)) {
                return stream
                        .filter(p -> Files.isRegularFile(p)
                                && p.getFileName().toString().equals(sessionId + ".jsonl"))
                        .findFirst()
                        .orElse(null);
            }
        } catch (Exception e) {
            log.debug("Failed to locate session transcript jsonl for {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 jsonl 会话审计为转录消息（续态缓存，增量解析）。
     * 行格式（SessionTranscriptWriter）：{@code message}（id/role/content=拼接文本）、
     * {@code tool_use}（parentId 指向消息行，toolCallId/name/input）、
     * {@code tool_result}（parentId 指向 tool_use 行，toolCallId/output）。
     * 后两类归组回 parent 消息的 content 块，与 state 版 transcript 的块形状保持一致。
     *
     * <p>归档是 append-only：缓存按 {@link ArchivedParseState} 保存解析续态，
     * 文件只追加时仅解析新增字节；文件被重写/截断时续态作废回退全量。</p>
     */
    private List<SessionTranscriptResponse.Message> parseArchivedTranscript(Path file) {
        try {
            ArchivedParseState state;
            synchronized (archivedTranscriptCache) {
                state = archivedTranscriptCache.get(file);
            }
            if (state != null
                    && Files.size(file) < state.consumedBytes) {
                // 文件被截断/轮转：续态失效，回退全量重解析
                state = null;
            }
            if (state == null) {
                state = new ArchivedParseState();
            }
            List<SessionTranscriptResponse.Message> snapshot;
            synchronized (state) {
                long size = Files.size(file);
                if (size > state.consumedBytes) {
                    resumeArchiveParse(state, file, size);
                }
                snapshot = buildArchiveSnapshot(state);
            }
            if (state.approxChars <= TRANSCRIPT_CACHE_MAX_ENTRY_CHARS) {
                // LRU 是有序 map（accessOrder），get/put 必须在同一把锁内，
                // 否则并发轮询会把"刚读到的次序"和"写入"交错，退化成无界增长。
                synchronized (archivedTranscriptCache) {
                    archivedTranscriptCache.put(file, state);
                    evictArchivedCacheOverBudget();
                }
            } else if (oversizedTranscripts.add(file)) {
                log.warn("Session transcript {} parses to ~{} chars — parsed per request but not "
                                + "cached (over the {} char cache-entry limit)",
                        file, state.approxChars, TRANSCRIPT_CACHE_MAX_ENTRY_CHARS);
            }
            return snapshot;
        } catch (Exception e) {
            log.warn("Failed to parse session transcript archive {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    /** 必须在持有 {@code archivedTranscriptCache} 锁时调用：按总字符预算淘汰最旧条目。 */
    private void evictArchivedCacheOverBudget() {
        long total = 0L;
        for (ArchivedParseState c : archivedTranscriptCache.values()) {
            total += c.approxChars;
        }
        while (total > TRANSCRIPT_CACHE_TOTAL_CHARS && archivedTranscriptCache.size() > 1) {
            Path eldest = archivedTranscriptCache.keySet().iterator().next();
            total -= archivedTranscriptCache.remove(eldest).approxChars;
        }
    }

    /**
     * 旧 jsonl 平铺思考的启发式判据（2026-09-12，教材会话 17/17 候选实测校准）：
     * assistant 正文以英文推理句式开头（第一人称推演/决策口吻）。真实回复是中文
     * 叙事或结构化输出，不会命中；且仅当同消息随后归组了 tool_use 才最终定案
     * （见 legacyThinkingMessages 清算逻辑），双重保护避免误伤。
     */
    private static final Pattern LEGACY_THINKING_PREFIX =
            Pattern.compile(
                    "^(The user|I need|I should|Let me|Now I|First|Next|So I|My plan|I'll|I must|Okay|Alright)\\b.*",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static boolean looksLikeLegacyThinking(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return LEGACY_THINKING_PREFIX.matcher(text.strip()).matches();
    }

    /**
     * 平台/框架内部通知（{@code <system-notification>…</system-notification>}）：
     * 仅供模型下一轮消费（后台子任务完成 hint、平台看护/恢复提示等），不属于
     * 用户可见对话内容。归档 jsonl 里 hint 会被框架拼进 assistant 正文
     * （InboxMiddleware 兼容行为），转录回填时在此剥离。
     */
    private static final Pattern SYSTEM_NOTIFICATION_BLOCK =
            Pattern.compile("<system-notification>[\\s\\S]*?</system-notification>");

    /** 剥离正文中的内部通知片段；截断/流式写入的未闭合尾巴一并剥到文末。 */
    private static String stripSystemNotification(String text) {
        if (text == null || !text.contains("<system-notification>")) {
            return text;
        }
        String stripped = SYSTEM_NOTIFICATION_BLOCK.matcher(text).replaceAll("");
        int open = stripped.indexOf("<system-notification>");
        if (open >= 0) {
            stripped = stripped.substring(0, open);
        }
        return stripped;
    }

    /**
     * 从续态 offset 续解析归档追加部分。必须在持有 state 锁时调用。
     *
     * <p>只消费到最后一个完整行（行尾含换行）为止——写侧按行追加，尾部可能存在
     * 尚未写完的半行，留待下次请求消费。UTF-8 多字节序列不含 0x0A，可按字节切行；
     * offset 全程按字节推进。文件首行与续态不符（被重写）或文件短于续态时，
     * 续态作废从头解析。</p>
     */
    private void resumeArchiveParse(ArchivedParseState state, Path file, long size)
            throws IOException {
        long start = state.consumedBytes;
        if (start > 0 && !Objects.equals(state.firstLine, readArchiveFirstLine(file))) {
            resetArchiveState(state);
            start = 0;
        }
        byte[] tail;
        try (InputStream in = Files.newInputStream(file)) {
            if (start > 0) {
                in.skipNBytes(start);
            }
            tail = in.readAllBytes();
        } catch (EOFException e) {
            // 文件短于续态（重写/截断竞态）：回退全量
            resetArchiveState(state);
            start = 0;
            try (InputStream in = Files.newInputStream(file)) {
                tail = in.readAllBytes();
            }
        }
        int lastNewline = lastIndexOfByte(tail, (byte) '\n');
        if (lastNewline < 0) {
            // 没有完整行可消费（含空文件）
            return;
        }
        int consumed = lastNewline + 1;
        String chunk = new String(tail, 0, consumed, StandardCharsets.UTF_8);
        String[] lines = chunk.split("\n", -1);
        if (start == 0) {
            state.firstLine = lines.length > 0 ? lines[0] : null;
        }
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            processArchiveLine(state, line);
        }
        state.consumedBytes = start + consumed;
    }

    /** 逐行解析归档行并归组进续态；必须在持有 state 锁时调用。 */
    private void processArchiveLine(ArchivedParseState state, String line) {
        Map<String, Object> entry;
        try {
            entry = objectMapper.readValue(line,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.debug("Skipping unparsable transcript line: {}", e.getMessage());
            return;
        }
        String type = String.valueOf(entry.get("type"));
        String entryId = String.valueOf(entry.get("id"));
        double ts = entry.get("timestamp") instanceof Number n ? n.doubleValue() : 0d;
        String createdAt = Instant.ofEpochSecond((long) ts).toString();
        long durationMillis = state.prevTs > 0 && ts > state.prevTs
                ? Math.round((ts - state.prevTs) * 1000) : 0L;
        state.prevTs = ts;
        if ("message".equals(type)) {
            String role = String.valueOf(entry.get("role")).toLowerCase();
            // 先剥离内部通知再走平铺思考启发式：hint 拼进正文时不应参与
            // 英文思考句式判定，也不应作为 assistant 正文回填到转录。
            String text = stripSystemNotification(String.valueOf(entry.get("content")));
            // thinking 与正文分离（框架 SessionTranscriptWriter 2026-09-12 起分字段
            // 保存）。旧 jsonl 无 thinking 字段：若本消息后续（同 entry 归组前无法
            // 预知）带工具调用且正文以英文思考句式开头，用启发式把整段正文判为思考。
            // 判据经实测校准：教材会话 17/17 候选全部含工具调用、零误伤——旧数据
            // 里"思考"被框架拼进 content，特征是 assistant + 首块英文推理口吻 +
            // 同消息有 tool_use；真正的中文叙事回复不以这些英文短语开头。
            String thinking = entry.get("thinking") instanceof String s
                    && !s.isBlank() ? s : null;
            String legacyThinking = null;
            if (thinking == null && "assistant".equals(role)
                    && looksLikeLegacyThinking(text)) {
                // 疑似旧格式平铺思考；tool_use 归组确认前保持候选身份，快照时清算
                legacyThinking = text;
                text = "";
            }
            List<Map<String, Object>> blocks = new ArrayList<>();
            String effectiveThinking = thinking != null ? thinking : legacyThinking;
            if (effectiveThinking != null) {
                state.approxChars += effectiveThinking.length();
                Map<String, Object> thinkingBlockMap = new HashMap<>();
                thinkingBlockMap.put("type", "thinking");
                thinkingBlockMap.put("id", entryId);
                thinkingBlockMap.put("thinking",
                        PathDisclosureSanitizer.sanitize(effectiveThinking));
                thinkingBlockMap.put("createdAt", createdAt);
                if (durationMillis > 0) {
                    thinkingBlockMap.put("durationSeconds",
                            Math.max(1, (int) Math.ceil(durationMillis / 1000.0)));
                }
                blocks.add(thinkingBlockMap);
            }
            if (text != null && !text.isBlank()) {
                Map<String, Object> textBlockMap = new HashMap<>();
                textBlockMap.put("type", "text");
                textBlockMap.put("id", entryId);
                textBlockMap.put("text", PathDisclosureSanitizer.sanitize(text));
                textBlockMap.put("createdAt", createdAt);
                blocks.add(textBlockMap);
            }
            // 正文剥空（如整条都是内部通知）时仍登记消息：后续 tool_use /
            // tool_result 行按 parentId 归组需要 owner；最终仍无任何块的
            // 消息在快照构建时剔除，不留空壳行。
            SessionTranscriptResponse.Message message = new SessionTranscriptResponse.Message(
                    entryId, role, null, createdAt, blocks, null);
            state.messageByEntryId.put(entryId, message);
            state.ordered.add(message);
            if (legacyThinking != null) {
                state.legacyThinkingMessages.put(entryId, message);
            }
        } else if ("tool_use".equals(type)) {
            String parentId = String.valueOf(entry.get("parentId"));
            SessionTranscriptResponse.Message owner = state.messageByEntryId.get(parentId);
            if (owner == null) {
                return;
            }
            // tool_use 归组到该消息 → 启发式确认:平铺内容确为思考
            state.legacyThinkingMessages.remove(parentId);
            String toolCallId = String.valueOf(entry.get("toolCallId"));
            String inputJson;
            try {
                inputJson = PathDisclosureSanitizer.sanitize(
                        objectMapper.writeValueAsString(entry.get("input")));
            } catch (Exception e) {
                log.debug("Skipping unparsable tool_use input: {}", e.getMessage());
                return;
            }
            state.approxChars += inputJson.length();
            Map<String, Object> block = new HashMap<>();
            block.put("type", "tool_call");
            block.put("id", toolCallId);
            block.put("name", String.valueOf(entry.get("name")));
            block.put("input", inputJson);
            block.put("state", "finished");
            block.put("createdAt", createdAt);
            owner.content().add(block);
            state.toolUseByEntryId.put(entryId, new PendingTool(toolCallId, owner));
        } else if ("tool_result".equals(type)) {
            String parentId = String.valueOf(entry.get("parentId"));
            PendingTool pending = state.toolUseByEntryId.get(parentId);
            if (pending == null) {
                return;
            }
            Map<String, Object> block = new HashMap<>();
            block.put("type", "tool_result");
            block.put("id", pending.toolCallId());
            block.put("name", String.valueOf(entry.get("name")));
            Object output = entry.get("output");
            String outputText = PathDisclosureSanitizer.sanitize(
                    output == null ? "" : String.valueOf(output));
            state.approxChars += outputText.length();
            block.put("output", outputText);
            block.put("state", "success");
            block.put("createdAt", createdAt);
            pending.owner().content().add(block);
        }
    }

    /**
     * 由续态构建本次请求的转录快照。必须在持有 state 锁时调用。
     *
     * <p>旧全量解析的两步收尾在这里等价落地，但都不改写续态：</p>
     * <ul>
     * <li>空壳剔除（内部通知剥离后未获得任何块、也无 tool_use 归组的消息）——
     *     过滤而非 removeIf，后续 tool_use 仍可归组进空壳消息让它复活；</li>
     * <li>平铺思考清算（无 tool_use 归组的候选退回 text）——候选消息以副本清算，
     *     共享消息对象保持候选原状，之后到来的 tool_use 仍能正常确认它。</li>
     * </ul>
     */
    private static List<SessionTranscriptResponse.Message> buildArchiveSnapshot(
            ArchivedParseState state) {
        List<SessionTranscriptResponse.Message> out = new ArrayList<>(state.ordered.size());
        for (SessionTranscriptResponse.Message m : state.ordered) {
            if (m.content().isEmpty()) {
                continue;
            }
            if (state.legacyThinkingMessages.containsKey(m.id())) {
                out.add(settledLegacyThinkingCopy(m));
            } else {
                out.add(m);
            }
        }
        return out;
    }

    /** 平铺思考清算的副本版：thinking 块退回 text 块，返回新消息对象，原对象不动。 */
    private static SessionTranscriptResponse.Message settledLegacyThinkingCopy(
            SessionTranscriptResponse.Message msg) {
        List<Map<String, Object>> blocks = new ArrayList<>(msg.content().size());
        String restored = null;
        for (Map<String, Object> block : msg.content()) {
            if (restored == null && "thinking".equals(block.get("type"))) {
                restored = String.valueOf(block.get("thinking"));
                continue;
            }
            blocks.add(block);
        }
        if (restored != null) {
            Map<String, Object> textBlock = new HashMap<>();
            textBlock.put("type", "text");
            textBlock.put("id", msg.id());
            textBlock.put("text", restored);
            textBlock.put("createdAt", msg.createdAt());
            blocks.add(0, textBlock);
        }
        return new SessionTranscriptResponse.Message(
                msg.id(), msg.role(), msg.name(), msg.createdAt(), blocks, msg.metadata());
    }

    private static void resetArchiveState(ArchivedParseState state) {
        state.messageByEntryId.clear();
        state.legacyThinkingMessages.clear();
        state.toolUseByEntryId.clear();
        state.ordered.clear();
        state.prevTs = 0d;
        state.approxChars = 0L;
        state.consumedBytes = 0L;
        state.firstLine = null;
    }

    /** 读文件首行原始字节（按 '\n' 截断，UTF-8 解码），供 append-only 守卫比对。 */
    private static String readArchiveFirstLine(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1 && b != '\n') {
                buf.write(b);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static int lastIndexOfByte(byte[] buf, byte b) {
        for (int i = buf.length - 1; i >= 0; i--) {
            if (buf[i] == b) {
                return i;
            }
        }
        return -1;
    }

    private SessionTranscriptResponse.Message toTranscriptMessage(Msg msg, long durationMillis) {
        String role = msg.getRole() == null ? "unknown" : msg.getRole().name().toLowerCase();
        String createdAt = normalizeTranscriptTimestamp(msg.getTimestamp());
        List<Map<String, Object>> content = new ArrayList<>();
        for (ContentBlock block : msg.getContent()) {
            Map<String, Object> out = toTranscriptBlock(block, msg.getId(), createdAt, durationMillis);
            if (out != null) {
                content.add(out);
            }
        }
        // 透传 synthetic 标记供前端静默渲染系统注入消息（任务投递/HITL 恢复/运维补发）。
        // 只复制标记本身，不整包透传历史消息元数据。
        Map<String, Object> metadata = null;
        if (msg.getMetadata() != null
                && Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC))) {
            metadata = Map.of("synthetic", true);
        }
        return new SessionTranscriptResponse.Message(
                msg.getId(), role, msg.getName(), createdAt, content, metadata);
    }

    /** 把 AgentScope ContentBlock 转成前端 AG-UI Msg 的块形状；不支持的块返回 null（跳过）。 */
    private Map<String, Object> toTranscriptBlock(
            ContentBlock block, String msgId, String createdAt, long durationMillis) {
        if (block instanceof TextBlock textBlock) {
            Map<String, Object> out = new HashMap<>();
            out.put("type", "text");
            out.put("id", msgId);
            out.put("text", PathDisclosureSanitizer.sanitize(
                    textBlock.getText() == null ? "" : textBlock.getText()));
            out.put("createdAt", createdAt);
            return out;
        }
        if (block instanceof ThinkingBlock thinkingBlock) {
            Map<String, Object> out = new HashMap<>();
            out.put("type", "thinking");
            out.put("id", msgId);
            out.put("thinking", PathDisclosureSanitizer.sanitize(
                    thinkingBlock.getThinking() == null ? "" : thinkingBlock.getThinking()));
            out.put("createdAt", createdAt);
            if (durationMillis > 0) {
                // 向上取整保底 1s:同秒写入的消息(毫秒差)也显示「已思考 1s」而非无时长
                out.put("durationSeconds", Math.max(1, (int) Math.ceil(durationMillis / 1000.0)));
            }
            return out;
        }
        if (block instanceof ToolUseBlock toolUse) {
            Map<String, Object> out = new HashMap<>();
            out.put("type", "tool_call");
            out.put("id", toolUse.getId());
            out.put("name", toolUse.getName());
            out.put("input", serializeToolInput(toolUse));
            out.put("state", mapToolCallState(toolUse.getState()));
            out.put("createdAt", createdAt);
            return out;
        }
        if (block instanceof ToolResultBlock toolResult) {
            Map<String, Object> out = new HashMap<>();
            out.put("type", "tool_result");
            out.put("id", toolResult.getId());
            out.put("name", toolResult.getName());
            out.put("output", flattenToolResult(toolResult));
            // transcript 是历史快照：running 状态的 tool_result 只可能是异步
            // 后台化占位符（"Tool 'xxx' is running in background…"，真正进行中
            // 的调用不会持久化 result），任务早已完成——归一为 success，
            // 否则历史里已完成的 agent_send 永久显示 spinner（2026-09-12 实测 4 处）。
            String resultState = mapToolResultState(toolResult.getState());
            if ("running".equals(resultState)) {
                resultState = "success";
            }
            out.put("state", resultState);
            out.put("createdAt", createdAt);
            return out;
        }
        // data / hint / image / audio 等块不在转录同步范围内
        return null;
    }

    private String serializeToolInput(ToolUseBlock toolUse) {
        Map<String, Object> input = toolUse.getInput();
        if (input != null && !input.isEmpty()) {
            try {
                return PathDisclosureSanitizer.sanitize(objectMapper.writeValueAsString(input));
            } catch (Exception e) {
                log.debug("Failed to serialize tool input for {}", toolUse.getId(), e);
            }
        }
        String content = toolUse.getContent();
        return (content == null || content.isBlank()) ? "{}" : PathDisclosureSanitizer.sanitize(content);
    }

    private String flattenToolResult(ToolResultBlock toolResult) {
        if (toolResult.getOutput() == null || toolResult.getOutput().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : toolResult.getOutput()) {
            if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(textBlock.getText());
            }
        }
        return PathDisclosureSanitizer.sanitize(sb.toString());
    }

    private String mapToolCallState(ToolCallState state) {
        if (state == null) {
            return "pending";
        }
        return switch (state) {
            case PENDING -> "pending";
            case ASKING -> "asking";
            case ALLOWED -> "allowed";
            case SUBMITTED -> "submitted";
            case FINISHED -> "finished";
        };
    }

    private String mapToolResultState(ToolResultState state) {
        if (state == null) {
            return "success";
        }
        return switch (state) {
            case SUCCESS -> "success";
            case ERROR -> "error";
            case RUNNING -> "running";
            case INTERRUPTED -> "interrupted";
            case DENIED -> "denied";
        };
    }

    /** 统一为 ISO-8601（T 分隔 + UTC），保证前端 JS Date 可直接解析。 */
    private String normalizeTranscriptTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value).toString();
        } catch (Exception ignored) {
            // fallthrough
        }
        try {
            String normalized = value.replace(' ', 'T');
            return LocalDateTime.parse(normalized).atZone(ZoneId.systemDefault()).toInstant().toString();
        } catch (Exception ignored) {
            return value;
        }
    }
}
