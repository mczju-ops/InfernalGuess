package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;

/**
 * 末影（{@code ender}）—— **攻击后随机传送**（原插件是「传送到目标背后」）。
 *
 * <h2>★ 用户第 11 条的两点要求</h2>
 * <ol>
 *   <li><b>「改为攻击后随机传送」</b> —— 原实现是瞬移到目标背后。
 *       两只怪都有 `ender` 时会**贴背二人转、永远打不到对方**（我在设计文档里
 *       把它列为「最可能打不完」的四条之一）。改成随机传送就天然打破了这个循环。</li>
 *   <li><b>落点检查只做两件事</b>（用户原话）：「确保落点 y 相同且脚下不为空气即可」
 *       —— 因为生产环境竞技场周围**只有虚空**，不需要更复杂的碰撞判定。</li>
 * </ol>
 *
 * <p>我们额外加了一条（很便宜）：落点本体与头顶必须是**可通行**的，
 * 否则会把怪塞进方块里。这不算超范围 —— 它服务的是同一个目的（落点可用）。
 *
 * <p>与炒鸡的第三个差别：去掉它那个 {@code IM_ENDER_SUPPRESSED_EXPIRES_AT}
 * 闪光封锁 —— 那个 PDC 键在炒鸡本体里**只有读取与清除、全仓库没有写入点**，
 * 是个从未接线的机制，不值得搬运。
 */
public final class EnderAffix implements Affix {

    /** 传送半径（原插件的 {@code distance: 2} 是「背后 2 格」，随机传送需要更大范围才有意义）。 */
    private static final double RADIUS = 6.0;
    private static final int MAX_ATTEMPTS = 16;
    private static final long COOLDOWN_TICKS = 60L;

    @Override
    public String id() {
        return "ender";
    }

    @Override
    public AffixType type() {
        return AffixType.DUAL;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 skills.Ender  .chance 的值，照抄。 */
    @Override
    public double chance() {
        return 0.5;
    }


    @Override
    public void onTrigger(AffixContext ctx) {
        LivingEntity holder = ctx.entity();
        if (holder == null || !holder.isValid() || holder.isDead()) {
            return;
        }
        Location from = holder.getLocation();
        if (from.getWorld() == null) {
            return;
        }
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            double dx = (Math.random() - 0.5) * 2.0 * RADIUS;
            double dz = (Math.random() - 0.5) * 2.0 * RADIUS;
            // ★ 用户指定：y 保持不变
            Location cand = from.clone().add(dx, 0, dz);

            // ★ 用户指定：脚下不能是空气（否则会掉进虚空）
            if (cand.clone().subtract(0, 1, 0).getBlock().getType().isAir()) {
                continue;
            }
            // 落点与头顶要能站（避免把怪塞进方块）
            if (!cand.getBlock().isPassable() || !cand.clone().add(0, 1, 0).getBlock().isPassable()) {
                continue;
            }
            Location before = holder.getLocation();
            holder.teleport(cand);
                        AffixFx.sound(cand, "ENTITY_ENDERMAN_TELEPORT", 1.0f, 1.0f);
            return;
        }
        // 16 次都没找到安全落点 → 放弃本次传送（不硬传，避免掉虚空）
    }
}
