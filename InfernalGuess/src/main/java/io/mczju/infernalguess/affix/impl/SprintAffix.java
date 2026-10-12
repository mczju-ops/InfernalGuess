package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 疾速（{@code sprint}）—— 装配时给怪物**永久速度**。
 *
 * <p>原实现：炒鸡 {@code StatSprintSkill}（{@code getId() = "sprint"}），
 * {@code SkillType.STAT}，{@code addPotionEffect(SPEED, INFINITE_DURATION, amplifier)}，
 * 并在 {@code onUnequip} 里 {@code removePotionEffect(SPEED)}。
 *
 * <h2>为什么它是最容易的一条</h2>
 * 它**完全与目标无关** —— 炒鸡的类注释自己都写明「不依赖附近是否有玩家」，
 * 所以在 mob-vs-mob 下本来就该生效，只是被炒鸡的触发路由挡住了（STAT 其实不受路由影响，
 * 这条是真正意义上的「零改动可用」）。
 */
public final class SprintAffix implements Affix {

    /** 速度等级。1 = 速度 II（药水等级 = amplifier + 1）。 */
    private static final int AMPLIFIER = 1;

    @Override
    public String id() {
        return "sprint";
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
                PotionEffectType.SPEED,
                PotionEffect.INFINITE_DURATION,
                AMPLIFIER,
                false,   // ambient：false，避免观战时的额外粒子噪音
                true));  // particles：保留（原插件也是 true，观战者能看到加速提示）
    }

    @Override
    public void onUnequip(AffixContext ctx) {
        LivingEntity entity = ctx.entity();
        if (entity == null || !entity.isValid()) {
            return;
        }
        entity.removePotionEffect(PotionEffectType.SPEED);
    }
}
