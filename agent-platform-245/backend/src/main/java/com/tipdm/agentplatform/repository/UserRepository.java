package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByOauthUserIdAndOauthProvider(String oauthUserId, String oauthProvider);

    boolean existsByUsername(String username);

    /**
     * 按关键字模糊查用户（授权选人用）。
     *
     * <p>命中 {@code username}（主键，唯一且非空，最可靠）或 {@code name}（可为空）。
     * <b>不搜邮箱与手机号</b>：这两列属个人信息，授权选人场景没有按它们找人的必要，
     * 开放等于把「能列出平台所有用户邮箱」变成任何持 {@code group:update} 账号的能力。
     * 需要按联系方式找人时应走用户自己可见的资料页。</p>
     *
     * <p>大小写不敏感（{@code lower()}），且用 {@code LIMIT} 在数据库侧截断——
     * 用户表随注册增长，前端只需 Top-N 个候选，全量返回既慢又构成用户枚举面。</p>
     *
     * @param keyword 用户名/姓名片段，两端按包含匹配
     * @param limit   最大返回条数，由调用方给出上限
     */
    @Query("""
            select u from User u
            where lower(u.username) like lower(concat('%', :keyword, '%'))
               or (u.name is not null and lower(u.name) like lower(concat('%', :keyword, '%')))
            order by u.username
            """)
    List<User> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
