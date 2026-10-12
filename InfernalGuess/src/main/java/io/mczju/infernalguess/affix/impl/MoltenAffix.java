package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Particle;

/**
 * 熔岩（{@code molten}）—— **谁打了这只怪，谁被点着**。
 *
 * <p>原实现：炒鸡 {@code PassiveMoltenSkill}，
 * {@code setFireTicks(max(当前, 60))}，削弱时 50% 概率直接跳过。
 *
 * <h2>✅ 用户第 5 条：无需额外处理</h2>
 * 「{@code lobby} 世界关闭了火焰蔓延，不会烧场地」—— 所以这里直接用原版燃烧即可
 * （我方 {@code world.gamerules.mobGriefing: false} 另有兜底）。
 *
 * <p>改造点只有一处：目标 {@code Player} → {@code LivingEntity}
 * （{@code setFireTicks} / {@code getFireTicks} 定义在 {@code Entity} 上，本来通用）。
 * 另外顺手把炒鸡那个「削弱 = 50% 概率跳过」的**特例**去掉 ——
 * 全项目只有它这么实现，语义分叉没必要保留。
 */
public final class MoltenAffix implements Affix {

    private static final int FIRE_TICKS = 60;        // 3 秒（同原插件）
    private static final long COOLDOWN_TICKS = 0L;   // ★ 原版无冷却（每次受击都触发），照抄  // ★ 我们的补充：3 秒

    @Override
    public String id() {
        return "molten";
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
        // 只延长不缩短（避免两个 molten 怪互相把对方的火压掉）
        int current = ctx.target().getFireTicks();
        ctx.target().setFireTicks(Math.max(current, FIRE_TICKS));

                    }
}
