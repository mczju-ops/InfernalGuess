package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixRegistry;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Location;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/**
 * 弓手（{@code archer}）—— 朝目标**射一串带散布的箭**。
 *
 * <p>原实现：炒鸡 {@code DualArcherSkill}（{@code SkillType.DUAL}），
 * {@code chance 0.5} / {@code arrow-count 3} / {@code speed 3.0} / {@code spread 0.1}，
 * 冷却 40 tick，两条分支（攻击 / 受击）都会触发。
 *
 * <h2>★ 我们修掉了原插件的瞄准偏差</h2>
 * 炒鸡用 {@code loc1.getY() + 1} 估算目标眼睛高度 —— 那是**按玩家体型估的**，
 * 对高/矮怪（劫掠兽、鸡、乌龟）会打偏。
 * 我们改用 {@code getEyeLocation()}，对任何实体都准
 * （原插件的 {@code isSmall(Entity)} 说明作者其实考虑过体型，只是没用在瞄准上）。
 *
 * <h2>★ 投射物必须打来源标记</h2>
 * {@link AffixRegistry#markSource} 让箭在命中时能被回溯到「谁射的」——
 * 否则：
 * <ul>
 *   <li>{@code BattleListener.onDamage} 的同阵营免伤**拦不住它**（箭不是 LivingEntity）</li>
 *   <li>会**射到自己队友**</li>
 * </ul>
 * 箭本身还会通过 {@code setShooter} 自动带上归属，两条路一起用更稳
 * （爆炸物没有 shooter，只能靠标记）。
 */
public final class ArcherAffix implements Affix {

    private static final int ARROW_COUNT = 3;
    private static final float SPEED = 3.0f;
    private static final float SPREAD = 0.1f;
    private static final long COOLDOWN_TICKS = 40L;   // 同原插件

    @Override
    public String id() {
        return "archer";
    }

    @Override
    public AffixType type() {
        return AffixType.DUAL;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 skills.Archer  .chance 的值，照抄。 */
    @Override
    public double chance() {
        return 0.5;
    }


    @Override
    public void onTrigger(AffixContext ctx) {
        LivingEntity self = ctx.entity();
        if (self == null || !self.isValid() || !ctx.hasValidTarget()) {
            return;
        }
        LivingEntity target = ctx.target();
        Location from = self.getEyeLocation();
        // ★ 用目标的真实眼高，而不是「+1」
        Vector dir = target.getEyeLocation().toVector()
                .subtract(from.toVector()).normalize();

        for (int i = 0; i < ARROW_COUNT; i++) {
            Arrow arrow = self.getWorld().spawnArrow(from, dir, SPEED, SPREAD, Arrow.class);
            arrow.setShooter(self);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);   // 不许被捡
            AffixRegistry.markSource(arrow, self);
        }
        AffixFx.sound(from, "ENTITY_ARROW_SHOOT", 0.8f, 1.0f);
    }
}
