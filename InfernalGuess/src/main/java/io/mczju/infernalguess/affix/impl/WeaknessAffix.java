package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Particle;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 虚弱（{@code weakness}）—— 给目标挂**虚弱**。
 *
 * <p>原实现：炒鸡 {@code DualWeaknessSkill}，{@code duration-ticks 500}（**25 秒**）、
 * {@code amplifier 1}（虚弱 II）、冷却 80 tick（**4 秒**）。
 *
 * <h2>★ 我们改掉了原插件最明显的一个失衡</h2>
 * 原配置是「**时长 25 秒 vs 冷却 4 秒**」→ 效果**永远覆盖得住冷却**
 * → 只要两只近战怪互相打，虚弱就是**常驻**状态，
 * 攻击力被压到极低 → **长时间打不死**（要靠毒雾磨血才收得了尾）。
 *
 * <p>我们的处理：**时长明显短于冷却**（5 秒 < 8 秒）→ 永远存在**空窗期**，
 * 攻防节奏有起伏，而不是一路被压平。
 *
 * <p>⚠️ 已知的偏科：虚弱只削**近战**伤害，对 {@code archer} / {@code firework} /
 * {@code ghastly} 这类投射物词条完全无效 → 强弱会两极分化。定价时注意。
 */
public final class WeaknessAffix implements Affix {

    /** 5 秒 —— 刻意**短于**冷却。 */
    private static final int DURATION_TICKS = 500;
    private static final int AMPLIFIER = 1;          // 虚弱 II
    /** 8 秒冷却 > 5 秒时长 → 必然有空窗期。 */
    private static final long COOLDOWN_TICKS = 80L;

    @Override
    public String id() {
        return "weakness";
    }

    @Override
    public AffixType type() {
        return AffixType.DUAL;
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
                PotionEffectType.WEAKNESS, DURATION_TICKS, AMPLIFIER, false, true));

                    }
}
