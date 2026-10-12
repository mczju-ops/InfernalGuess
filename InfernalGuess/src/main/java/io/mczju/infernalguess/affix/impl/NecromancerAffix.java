package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixRegistry;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.WitherSkull;
import org.bukkit.util.Vector;

/**
 * 死灵（{@code necromancer}）—— 周期性地朝当前目标**发射凋灵之首**。
 *
 * <p>原实现：炒鸡 {@code RangeNecromancerSkill}（{@code SkillType.RANGE}），
 * {@code range 14} / {@code chance 0.3} / 冷却 100 tick，
 * 与 {@code ghastly} 共享 40 tick 的投射物冷却（两者错开释放）。
 *
 * <h2>★ 补上炒鸡漏掉的两件事</h2>
 * <ol>
 *   <li><b>寿命兜底</b> —— 炒鸡这条**没有** {@code scheduleProjectileLifetime}
 *       （{@code ghastly} 有），空放的凋灵之首会一直飞。我们用
 *       {@link ProjectileLifetime} 补上（这也是把工具抽出来的理由）。</li>
 *   <li><b>可控伤害 + 不炸地形</b>：{@code setYield(0)} + metadata，
 *       由 {@code BattleListener.onProjectileHit} 只对命中实体结算。</li>
 * </ol>
 *
 * <p>与 {@code ghastly} 的共享冷却：这里用同一个 buff 键做 40 tick 错峰，
 * 复现原插件的「两个投射物技能不会同一瞬间一起放」。
 */
public final class NecromancerAffix implements Affix {

    private static final double DAMAGE = 6.0;
    private static final double VELOCITY = 1.0;
    private static final int LIFETIME_TICKS = 100;
    private static final long COOLDOWN_TICKS = 100L;

    /** 与 {@code ghastly} 共享的错峰键（同原插件的 {@code last_projectile_tick}）。 */
    static final String KEY_SHARED_PROJECTILE = "shared:projectile";
    private static final long SHARED_GAP_TICKS = 40L;

    @Override
    public String id() {
        return "necromancer";
    }

    @Override
    public AffixType type() {
        return AffixType.RANGE;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 skills.Necromancer  .chance 的值，照抄。 */
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
        long now = ctx.currentTick();
        // 与 ghastly 错峰（复现原插件的共享投射物冷却）
        if (ctx.runtime().hasBuff(KEY_SHARED_PROJECTILE, now)) {
            return;
        }
        ctx.runtime().setBuff(KEY_SHARED_PROJECTILE, now + SHARED_GAP_TICKS);

        Location from = holder.getEyeLocation();
        Vector dir = ctx.target().getEyeLocation().toVector()
                .subtract(from.toVector()).normalize();

        WitherSkull skull = holder.getWorld().spawn(from, WitherSkull.class, s -> {
            s.setDirection(dir.clone().multiply(VELOCITY));
            s.setCharged(false);     // 不蓄力（蓄力的爆炸会破坏方块）
            s.setYield(0f);          // ★ 不爆炸，伤害交给我们结算
        });
        AffixRegistry.markProjectile(skull, holder, DAMAGE);
        ProjectileLifetime.schedule(skull, ctx.plugin(), LIFETIME_TICKS);
            }
}
