package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.User;
import com.tipdm.agentplatform.repository.UserRepository;
import com.tipdm.agentplatform.web.dto.UserDtos.UserSearchHit;
import com.tipdm.agentplatform.web.dto.UserDtos.UserSearchResponse;
import com.tipdm.agentplatform.web.error.ApiException;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户检索（授权选人用）。
 *
 * <p>独立成服务而不并入 {@link UserService}：后者已混杂注册、验证码、
 * 密码重置、对外 HTTP 转发与加解密，再塞检索会让它继续膨胀。
 * 检索是只读、无副作用的独立关注点。</p>
 *
 * <p><b>这是一个用户枚举面，故刻意收窄</b>：</p>
 * <ul>
 *   <li>只搜 {@code username} 与 {@code name}，不搜邮箱 / 手机号
 *       （见 {@link UserRepository#searchByKeyword} 的理由）；</li>
 *   <li>必须给关键词才查，不提供「列出全部用户」的全量端点；</li>
 *   <li>结果数在数据库侧截断，前端拿不到超过 {@link #MAX_LIMIT} 条。</li>
 * </ul>
 * 之所以可以这样做：调用方是「把某人加进群组」——这是个窄到只需挑一个具体人的场景，
 * 不需要目录浏览。
 */
@Service
public class UserSearchService {

    /** 单次返回上限。搜索框只需 Top-N 候选，再多没有 UI 去消化。 */
    public static final int MAX_LIMIT = 20;

    /** 关键词最短长度：1 个字符会命中大批用户，枚举面过大且无筛选价值。 */
    private static final int MIN_KEYWORD_LENGTH = 2;

    private final UserRepository userRepository;

    public UserSearchService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserSearchResponse search(String rawKeyword) {
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        if (keyword.length() < MIN_KEYWORD_LENGTH) {
            // 不是错误语义（用户还在打字），用 badRequest 让前端静默不查即可
            throw ApiException.badRequest("搜索关键词至少 " + MIN_KEYWORD_LENGTH + " 个字符");
        }

        List<User> users =
                userRepository.searchByKeyword(keyword, PageRequest.of(0, MAX_LIMIT));
        List<UserSearchHit> hits = users.stream()
                .map(u -> new UserSearchHit(u.getId(), displayName(u), u.getUsername()))
                .toList();
        return new UserSearchResponse(hits, hits.size(), keyword);
    }

    /**
     * 展示名回退：{@code name} 非空取 name，否则取 username。
     *
     * <p>与 {@code GroupService} 渲染群组成员时的规则<b>保持一致</b>——
     * 否则搜索结果里显示的名字会与加进群组后列表里显示的不一致，
     * 用户会以为是两个人。</p>
     */
    private static String displayName(User u) {
        return u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getUsername();
    }
}
