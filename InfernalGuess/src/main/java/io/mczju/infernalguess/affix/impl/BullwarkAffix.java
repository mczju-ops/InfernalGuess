package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 壁垒（{@code bullwark}）—— 装配时给怪物**永久抗性提升 III**。
 *
 * <p>原实现：炒鸡 {@code StatBullwarkSkill}，{@code addPotionEffect(RESISTANCE, -1, 2)}。
 *
 * <h2>⚠️ 配平提醒</h2>
 * 抗性 III 大致是「受到的伤害 ×0.4」量级，会**显著拖长对局**；
 * 与 {@code armoured} / {@code 1up} 叠加时容易变成「打不死」。
 * 所以我们把 {@code weight-bonus} 定价得高一些（见 {@code mob-groups.yml}），
 * 并且在 `docs/13` §8 里记了这条风险。
 *
 * <p>抗性只取**最高等级**，所以它与 {@code armoured} 的「抗性 II」不叠加 ——
 * 同时抽到两者时以本词条的 III 为准（符合预期）。
 */
public final class BullwarkAffix implements Affix {

    /** 抗性等级。2 = 抗性提升 III。 */
    private static final int AMPLIFIER = 2;

    @Override
    public String id() {
        return "bullwark";
    }

    @Override
    public AffixType type() {
        return AffixType.STAT;
    }

    @Override
    public void onEquip(AffixContext ctx) {
        LivingEntity entity = ctx.entity();
        if (entity == null || !entity.isValid()) {
            return;
        }
        entity.addPotionEffect(new PotionEffect(
                PotionEffectType.RESISTANCE,
                PotionEffect.INFINITE_DURATION,
                AMPLIFIER,
                false,
                true));
    }

    @Override
    public void onUnequip(AffixContext ctx) {
        LivingEntity entity = ctx.entity();
        if (entity == null || !entity.isValid()) {
            return;
        }
        entity.removePotionEffect(PotionEffectType.RESISTANCE);
    }
}
