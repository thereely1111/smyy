package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.PlatformSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 系统配置覆盖层（{@code platform_setting}）。
 *
 * <p>访问路径永远是「按配置键读写」，故主键即配置键，没有按值/时间查询的用法。
 * 需要查询当前覆盖了哪些项时用 {@link #findAll()}，调用方
 * （{@code SettingOverrideService}）会自行过滤掉未登记的键。</p>
 */
@Repository
public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, String> {
}
