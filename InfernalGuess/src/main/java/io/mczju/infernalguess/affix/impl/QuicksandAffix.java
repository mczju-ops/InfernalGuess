package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Particle;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 流沙（{@code quicksand}）—— **谁打了这只怪，谁被减速**。
 *
 * <p>原实现：炒鸡 {@code PassiveQuicksandSkill}，
 * {@code ctx.getTargetPlayer().addPotionEffect(SLOWNESS, 180, 1)}。
 *
 * <h2>为什么它是「改造收益最直接」的一条</h2>
 * {@code SLOWNESS} 走的是 {@code MOVEMENT_SPEED} 属性修饰符，**对怪物同样有效**
 * （不像 {@code BLINDNESS} / {@code NAUSEA} / {@code HUNGER} 那样是玩家专属）。
 * 所以只需把目标类型放宽即可。
 *
 * <p>⚠️ 玩法风险（已在 docs/13 §8 记账）：减速会让「追不上」的僵局出现。
 * 双方都被减速时可能互相追不上 → 拖到超时（由毒雾与超时裁判兜底）。
 */
public final class QuicksandAffix implements Affix {

    private static final int DURATION_TICKS = 180;   // 9 秒（同原插件）
    private static final int AMPLIFIER = 1;          // 缓慢 II
    private static final long COOLDOWN_TICKS = 0L;   // ★ 原版无冷却（每次受击都触发），照抄  // ★ 我们的补充：3 秒

    @Override
    public String id() {
        return "quicksand";
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
                PotionEffectType.SLOWNESS, DURATION_TICKS, AMPLIFIER, false, true));

        // 视觉：脚下一圈沙尘。
        // ★ 注意 Particle.FALLING_DUST **必须带 BlockData**，否则会抛
        //   IllegalArgumentException（实测踩过）——所以走 ringBlock 而不是 ring。
                    }
}
