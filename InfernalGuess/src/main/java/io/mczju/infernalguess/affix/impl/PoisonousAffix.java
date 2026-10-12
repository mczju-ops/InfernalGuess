package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Particle;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 剧毒（{@code poisonous}）—— **谁打了这只怪，谁中毒**。
 *
 * <p>原实现：炒鸡 {@code PassivePoisonousSkill}（{@code SkillType.PASSIVE}），
 * 从 {@code CombatListener} 的「玩家打怪」分支进入，
 * {@code ctx.getTargetPlayer().addPotionEffect(POISON, 200, 1)}。
 *
 * <h2>改造要点</h2>
 * <ol>
 *   <li>目标从 {@code Player} 换成 {@code LivingEntity} —— 药水效果 API 本来就面向实体，
 *       **一行类型都不用改逻辑**</li>
 *   <li>★ **显式声明冷却**：炒鸡这条**没有冷却**，而它的 PASSIVE 默认冷却就是 0
 *       → 每次被命中都会重挂，高频互殴下等于常驻中毒 II。
 *       我们按「中毒是量级不小的真伤」给它 3 秒冷却</li>
 * </ol>
 *
 * <h2>⚠️ 玩法风险（已在 docs/13 §8 记账）</h2>
 * 中毒是**无视护甲的真伤**。双方互相中毒会同时掉血，
 * 极端情况下**同一 tick 双灭** —— 而双灭时 {@code BattleTracker.decideWinner()}
 * 返回 {@code null}（视为平局，由调用方处理）。这条要靠毒雾与超时裁判兜底。
 */
public final class PoisonousAffix implements Affix {

    /** 中毒持续时间（tick）。原插件 200 = 10 秒。 */
    private static final int DURATION_TICKS = 200;

    /** 中毒等级。1 = 中毒 II。 */
    private static final int AMPLIFIER = 1;

    /**
     * 冷却（tick）。
     *
     * <p>★ 这是**我们与炒鸡的有意差别**：炒鸡这里是 0（每次被命中都重挂）。
     * 3 秒冷却既保留「反制打它的敌人」的手感，又不至于让整场变成永久中毒。
     */
    private static final long COOLDOWN_TICKS = 0L;   // ★ 原版无冷却（每次受击都触发），照抄

    @Override
    public String id() {
        return "poisonous";
    }

    @Override
    public AffixType type() {
        return AffixType.PASSIVE;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    @Override
    public void onTrigger(AffixContext ctx) {
        if (!ctx.hasValidTarget()) {
            return;
        }
        ctx.target().addPotionEffect(new PotionEffect(
                PotionEffectType.POISON,
                DURATION_TICKS,
                AMPLIFIER,
                false,
                true));

        // ★ 视觉反馈（原插件没有粒子 —— 这是我们为了「观战能看出发生了什么」加的）：
        //   在受害者身上爆一圈绿色粒子 + 一声闷响。
                    }
}
