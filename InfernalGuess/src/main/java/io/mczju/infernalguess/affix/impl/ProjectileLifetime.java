package io.mczju.infernalguess.affix.impl;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * 词条投射物的**寿命兜底**：到点还没命中就自行移除。
 *
 * <p>为什么需要：炒鸡的 {@code ghastly} 有这道兜底，但 {@code necromancer} **漏了** ——
 * 空放的凋灵之首会**一直飞**，在竞技场里就是永久残留的实体。
 * 抽成一个共用小工具，避免再漏。
 */
final class ProjectileLifetime {

    private ProjectileLifetime() {
    }

    static void schedule(Entity projectile, Plugin plugin, int ticks) {
        if (projectile == null || plugin == null) {
            return;
        }
        try {
            projectile.getScheduler().runDelayed(plugin, task -> {
                if (projectile.isValid()) {
                    projectile.remove();
                }
            }, null, ticks);
        } catch (Throwable ignored) {
            // 调度失败（如实体已卸载）→ 放弃兜底，不影响对局
        }
    }
}
