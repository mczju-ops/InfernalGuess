package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixRegistry;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Location;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/**
 * 恶魂（{@code ghastly}）—— 周期性地朝当前目标**发射火球**。
 *
 * <p>原实现：炒鸡 {@code RangeGhastlySkill}（{@code SkillType.RANGE}），
 * {@code range 12} / {@code chance 0.3} / 冷却 80 tick，火球带 {@code explosion-power 1}。
 *
 * <h2>★ 三处与炒鸡不同</h2>
 * <ol>
 *   <li><b>触发方式</b>：炒鸡的 RANGE **不 fire 任何事件**，只在
 *       {@code CombatService.tickRangeSkills} 里轮询「最近的玩家」。
 *       我们改由 {@code BattleService} 的 5 tick 心跳驱动，目标取
 *       {@code mob.getTarget()}（索敌本来就是我们的 {@code EnemyTargetGoal} 负责的）。</li>
 *   <li><b>不炸地形</b>：炒鸡的 {@code explosion-power: 1} 会真炸方块
 *       （它只关了 {@code setIsIncendiary} 防点燃）。我们设 <b>{@code setYield(0)}</b>，
 *       把伤害写进 metadata，由 {@code BattleListener.onProjectileHit}
 *       **只对被命中的实体**结算 —— 这是我们的红线（不得破坏场地）。</li>
 *   <li><b>保留寿命兜底</b>（炒鸡这条有，值得学）：空放的火球 100 tick 后自动移除。</li>
 * </ol>
 *
 * <p>⚠️ 火球是独立实体 → 必须打来源标记，否则同阵营免伤拦不住、会炸到队友。
 */
public final class GhastlyAffix implements Affix {

    private static final double DAMAGE = 8.0;
    private static final double VELOCITY = 1.2;
    private static final int LIFETIME_TICKS = 100;
    private static final long COOLDOWN_TICKS = 80L;

    @Override
    public String id() {
        return "ghastly";
    }

    @Override
    public AffixType type() {
        return AffixType.RANGE;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 skills.Ghastly  .chance 的值，照抄。 */
    @Override
    public double chance() {
        return 0.3;
    }


    @Override
    public void onTrigger(AffixContext ctx) {
        LivingEntity holder = ctx.entity();
        if (holder == null || !holder.isValid() || !ctx.hasValidTarget()) {
            return;
        }
        Location from = holder.getEyeLocation();
        Vector dir = ctx.target().getEyeLocation().toVector()
                .subtract(from.toVector()).normalize();

        Fireball fb = holder.getWorld().spawn(from, Fireball.class, f -> {
            f.setDirection(dir.clone().multiply(VELOCITY));
            f.setFireTicks(0);
            f.setIsIncendiary(false);
            f.setYield(0f);          // ★ 不爆炸（伤害由我们的命中结算接管）
        });
        AffixRegistry.markProjectile(fb, holder, DAMAGE);
        ProjectileLifetime.schedule(fb, ctx.plugin(), LIFETIME_TICKS);
            }
}
