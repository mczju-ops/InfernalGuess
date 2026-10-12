package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;

/**
 * 狂暴（{@code berserk}）—— **自己掉血，换目标额外受伤**。
 *
 * <p>原实现：炒鸡 {@code ActiveBerserkSkill}，
 * {@code setHealth(max(0, health - selfDamage))} + {@code victim.damage(extraDamage, mob)}，
 * 默认 {@code self-damage: 1} / {@code extra-damage: 5}，冷却 60 tick。
 *
 * <h2>★ 我们改掉了原插件的一个真缺陷</h2>
 * 炒鸡用 {@code entity.setHealth(...)} 扣自己的血 —— 这**绕过了伤害事件**，后果有三：
 * <ol>
 *   <li>不吃 {@code 1up}（原插件的 {@code onMobDamaged} 只处理事件伤害）</li>
 *   <li>死亡归属异常（不是「被谁打死」）</li>
 *   <li>可能直接把怪弄死（{@code setHealth(0)}）</li>
 * </ol>
 * 我们改成走 {@code damage()}（正常伤害事件），并**加了一条自保**：
 * 只有扣完还剩 ≥1 点血时才自伤 —— 避免「狂暴到自杀」。
 *
 * <h2>为什么会递归安全</h2>
 * 本词条造成的伤害会重新进入 {@code EntityDamageByEntityEvent}，
 * 但 {@code AffixRegistry} 的 {@link io.mczju.infernalguess.affix.AffixRegistry} 派发深度守卫
 * 会**在词条产生的伤害上停止再派发** → 不会出现「A 打 B、B 反打 A」的无限链。
 */
public final class BerserkAffix implements Affix {

    private static final double SELF_DAMAGE = 1.0;
    private static final double EXTRA_DAMAGE = 5.0;
    private static final long COOLDOWN_TICKS = 60L;   // 同原插件

    @Override
    public String id() {
        return "berserk";
    }

    @Override
    public AffixType type() {
        return AffixType.ACTIVE;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    @Override
    public void onTrigger(AffixContext ctx) {
        LivingEntity self = ctx.entity();
        if (self == null || !self.isValid() || self.isDead() || !ctx.hasValidTarget()) {
            return;
        }
        // 自伤走正常伤害事件；扣完至少留 1 点血，避免「狂暴到自杀」
        if (self.getHealth() - SELF_DAMAGE >= 1.0) {
            self.damage(SELF_DAMAGE);
                    }
        // 额外伤害归因给这只怪（死亡播报才认得出是谁打的）
        ctx.target().damage(EXTRA_DAMAGE, self);
        AffixFx.sound(self.getLocation(), "ENTITY_PHANTOM_BITE", 0.7f, 0.8f);
    }
}
