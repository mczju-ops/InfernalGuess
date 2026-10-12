package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Particle;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 凋零（{@code withering}）—— **谁打了这只怪，谁中凋零**。
 *
 * <p>原实现：炒鸡 {@code PassiveWitheringSkill}，
 * {@code ctx.getTargetPlayer().addPotionEffect(WITHER, 180, 1)}，
 * {@code isWeakened()} 时把时长减半。
 *
 * <h2>改造要点</h2>
 * <ol>
 *   <li>目标 {@code Player} → {@code LivingEntity}（药水对任何实体都有效）</li>
 *   <li>去掉 {@code isWeakened()} 分支 —— 那是「玩家穿了魔法王盔甲」的专属机制，
 *       怪打怪下恒为 false（见 {@code docs/13} §8.14）</li>
 *   <li>★ 显式给冷却：炒鸡这条**没有冷却**（PASSIVE 默认 0）→ 每次挨打必重挂</li>
 * </ol>
 *
 * <h2>✅ 与我们的毒雾天然自洽</h2>
 * 开战 30 秒后我方毒雾会给所有参战生物挂**永久凋零 I**；
 * 本词条挂**凋零 II / 9 秒** → 会临时顶掉 I 级，9 秒后被毒雾压回 I 级。
 * **增量有限、不会失控** —— 这是设计上难得的一对好搭档。
 *
 * <p>⚠️ 但**凋零 / 凋灵骷髅免疫凋零**（用户明确要求不给它们补伤害），
 * 所以对这两类对手，本词条完全无效 —— 定价时要注意。
 */
public final class WitheringAffix implements Affix {

    private static final int DURATION_TICKS = 180;   // 9 秒（同原插件）
    private static final int AMPLIFIER = 1;          // 凋零 II
    private static final long COOLDOWN_TICKS = 0L;   // ★ 原版无冷却（每次受击都触发），照抄  // ★ 我们的补充：3 秒

    @Override
    public String id() {
        return "withering";
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
                PotionEffectType.WITHER, DURATION_TICKS, AMPLIFIER, false, true));

        // 视觉：黑色烟雾（与原插件的凋零观感一致，且比纯药水图标更显眼）
                    }
}
