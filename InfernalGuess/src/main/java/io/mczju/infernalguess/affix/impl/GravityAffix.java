package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Particle;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 重力（{@code gravity}）—— 让目标**漂浮起来**。
 *
 * <p>原实现：炒鸡 {@code RangeGravitySkill}（{@code SkillType.RANGE}），
 * 周期性地给「最近的玩家」挂漂浮 0 级 5 秒，还有三层玩家专属抵抗判定
 * （已有漂浮则跳过、{@code gravity_charm} 快捷栏护符 30%/60%/100% 抵抗）。
 *
 * <h2>★ 类型走过两轮裁定（现状 = RANGE 周期轮询）</h2>
 * <ol>
 *   <li><b>2026-10-11（用户第 13 条）</b>：曾把类型从 {@code RANGE} 改为
 *       {@link AffixType#ACTIVE}「攻击时赋予效果」；并去掉三层玩家专属判定
 *       （护符是玩家快捷栏物品，对怪毫无意义 —— 这条**保留**）。</li>
 *   <li><b>2026-10-12（用户新裁定）</b>：改回**周期轮询** {@link AffixType#RANGE}，
 *       <b>概率 0.3 与冷却 100 tick 沿用不变</b>。轮询目标是该怪当前的目标
 *       （{@code mob.getTarget()}），由 {@code BattleService} 的 5 tick 心跳驱动；
 *       与其它 RANGE 词条一致：冷却在概率判定**之前**就被消耗，所以实发间隔 ≥ 100 tick。</li>
 *   <li>数值：<b>漂浮 V、持续 2 秒</b>（40 tick；用户 2026-10-12 由 1 秒放宽到 2 秒）——
 *       漂浮 V 会把目标迅速抬升，2 秒仍属「抛起一下」的量级，
 *       制造「敌人被抛起、短暂够不着」的战术效果，又不会让对方一直浮在天上把对局拖成磨血
 *       （原插件的 0 级 5 秒会让近战怪长时间够不到对方）。</li>
 * </ol>
 *
 * <p>⚠️ 双方都是近战且都被抛起时，仍可能出现短暂的「谁也够不到谁」；
 * 由 30 秒后的毒雾与超时裁判兜底，不会卡死。
 */
public final class GravityAffix implements Affix {

    /** 漂浮等级：5 级（药水等级 = amplifier + 1）。 */
    private static final int AMPLIFIER = 4;
    /** 2 秒（用户 2026-10-12 指定；此前为 1 秒 / 20 tick）。 */
    private static final int DURATION_TICKS = 40;
    private static final long COOLDOWN_TICKS = 100L;

    @Override
    public String id() {
        return "gravity";
    }

    @Override
    public AffixType type() {
        // 2026-10-12 用户裁定：改回周期轮询（RANGE），概率/冷却照旧
        return AffixType.RANGE;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 skills.Gravity  .chance 的值，照抄。 */
    @Override
    public double chance() {
        return 0.3;
    }


    @Override
    public void onTrigger(AffixContext ctx) {
        if (!ctx.hasValidTarget()) {
            return;
        }
        // 已有漂浮就不再叠加（原插件也有这条，但它对玩家是为了防连续控制）
        if (ctx.target().hasPotionEffect(PotionEffectType.LEVITATION)) {
            return;
        }
        ctx.target().addPotionEffect(new PotionEffect(
                PotionEffectType.LEVITATION, DURATION_TICKS, AMPLIFIER, false, true));

                AffixFx.sound(ctx.target().getLocation(), "ENTITY_SHULKER_SHOOT", 0.6f, 1.2f);
    }
}
