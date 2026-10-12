package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/**
 * 雷暴（{@code storm}）—— 在目标头顶**召唤闪电**。
 *
 * <p>原实现：炒鸡 {@code DualStormSkill}，直接
 * {@code target.getWorld().strikeLightning(target.getLocation())} —— **真实闪电**，
 * 原版会点燃可燃方块，且配置里的 {@code cooldown-ticks: 0}（每次受击都掷）。
 *
 * <h2>★ 用户第 9 条：「不会烧竞技场」→ 但仍做一处收紧</h2>
 * 用户说场地不会烧（世界已关火焰蔓延 / 竞技场周围是虚空），所以**不强制改效果**。
 * 我们改用 {@code strikeLightningEffect}（**纯视觉、不造成伤害、不点燃**）
 * + 显式 {@code target.damage(...)} —— 好处是**伤害归因明确**：
 * 真实闪电劈死的怪，Bukkit 的 {@code getKiller()} 是 null，
 * 死亡播报与统计都认不出是谁打的（炒鸡源码未处理这点）。
 *
 * <h2>★ 2026-10-12 用户裁定：类型 DUAL → {@link AffixType#RANGE}（周期轮询）</h2>
 * 与 {@code ghastly}/{@code necromancer}/{@code tosser}/{@code wardenwrath}/{@code gravity} 同机制：
 * 由 5 tick 心跳驱动，目标是该怪当前的目标；**概率 0.22 照旧**。
 *
 * <h2>★ 冷却：0 → 40 tick（2 秒）—— 用户 2026-10-12 指定</h2>
 * 原版这条 {@code cooldown-ticks} 是 0（照抄过），但 0 冷却在 **RANGE 下含义完全变了**：
 * 心跳每 5 tick 就会掷一次 22% ⇒ 期望约 **1.15 秒一发**（≈4.3 HP/s），频率与观感都过密。
 * 用户拍板加 40 tick 冷却。按 {@code trigger(...)}「**先扣冷却、再掷概率**」的语义：
 * <pre>
 *   每 40 tick（2 秒）掷一次 22%  →  期望间隔 ≈ 40 / 0.22 ≈ 182 tick ≈ <b>9.1 秒一发</b>
 *   折算期望伤害 ≈ 5 / 9.1 ≈ 0.55 HP/s
 * </pre>
 * ⚠️ 注意：**概率没过也照样消耗冷却**，所以最坏情况是连续几次都不劈，而不是"没劈就立刻重掷"。
 *
 * <p>⚠️ 闪电是**无差别**的：真实闪电会劈到附近的观众与队友。
 * 用 `strikeLightningEffect` 之后这个问题自动消失。
 */
public final class StormAffix implements Affix {

    private static final double DAMAGE = 5.0;
    /** 40 tick（2 秒）—— 2026-10-12 用户指定；原版是 0（RANGE 下 0 冷却会变成每 5 tick 掷一次）。 */
    private static final long COOLDOWN_TICKS = 40L;

    @Override
    public String id() {
        return "storm";
    }

    @Override
    public AffixType type() {
        // 2026-10-12 用户裁定：DUAL → RANGE（周期轮询，概率/冷却照旧）
        return AffixType.RANGE;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 skills.Storm  .chance 的值，照抄。 */
    @Override
    public double chance() {
        return 0.22;
    }


    @Override
    public void onTrigger(AffixContext ctx) {
        LivingEntity holder = ctx.entity();
        if (holder == null || !holder.isValid() || !ctx.hasValidTarget()) {
            return;
        }
        LivingEntity target = ctx.target();
        Location at = target.getLocation();

        // 纯视觉闪电（不点燃、不造成伤害）
        try {
            at.getWorld().strikeLightningEffect(at);
        } catch (Throwable ignored) {
            // 极端情况下（世界不可用）跳过视觉，伤害照常
        }
        // 伤害由我们显式结算 → 归因给施法者，死亡播报认得出
        target.damage(DAMAGE, holder);
            }
}
