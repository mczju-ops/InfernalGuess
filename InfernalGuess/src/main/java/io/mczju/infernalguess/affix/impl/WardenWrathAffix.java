package io.mczju.infernalguess.affix.impl;

import io.mczju.infernalguess.affix.Affix;
import io.mczju.infernalguess.affix.AffixContext;
import io.mczju.infernalguess.affix.AffixFx;
import io.mczju.infernalguess.affix.AffixType;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/**
 * 声波咆哮（{@code wardenwrath}）—— **周期性地**朝当前目标释放**监守者式声波**：
 * 按距离衰减的伤害 + 击退 + 一条 SONIC_BOOM 粒子射线。
 *
 * <p>原实现：炒鸡 {@code PassiveWardenWrathSkill}，
 * {@code chance 0.25} / {@code damage 10} / {@code max-range 15} /
 * {@code decay-min-multiplier 0.2} / 冷却 80 tick。
 *
 * <h2>★ 2026-10-12 用户裁定：类型 PASSIVE → {@link AffixType#RANGE}（周期轮询）</h2>
 * 不再等「被打中」才反击，而是像 {@code ghastly}/{@code necromancer}/{@code tosser} 一样，
 * 由 {@code BattleService} 的 5 tick 心跳驱动轮询；**概率 0.25 与冷却 80 tick 照旧**。
 * 轮询时的目标是**该怪当前的目标**（{@code mob.getTarget()}，brain 型读的就是 brain 的记忆），
 * 没有目标就自然不触发（{@code ctx.hasValidTarget()} 守卫）。
 *
 * <p>⚠️ 与其它 RANGE 词条一致：冷却在 `trigger(...)` 里**先于概率判定**被消耗，
 * 所以「概率没过」或「距离超过 15 格提前返回」这一轮也照样进入冷却 —— 实发间隔 ≥ 80 tick。
 *
 * <h2>改造要点</h2>
 * <ol>
 *   <li>靶子从 {@code Player} 换成 {@code LivingEntity} ——
 *       {@code getHeight()} / {@code knockback(...)} / {@code damage(double, Entity)}
 *       都定义在 {@code LivingEntity} 上，**API 一行都不用换**
 *       （已用 javap 对 paper-api 26.2 逐个核对过）</li>
 *   <li>去掉玩家的「位移免疫」判定（那是玩家 PDC 上的键，对怪恒为空）</li>
 *   <li>★ 去掉炒鸡那个**双重击退**：原实现既走 {@code damage(dmg, mob)}
 *       （原版自带击退）又手工 {@code knockback(...)}，玩家身上是刻意为之，
 *       但怪打怪下会叠成两倍。我们**只保留手工击退**，并用无来源伤害结算，
 *       避免原版再补一次。</li>
 * </ol>
 *
 * <p>视觉是这条词条的亮点：一条从怪眼射向目标的声波射线，观战者一眼能看懂发生了什么。
 */
public final class WardenWrathAffix implements Affix {

    private static final double BASE_DAMAGE = 10.0;
    private static final double MAX_RANGE = 15.0;
    private static final double MIN_MULTIPLIER = 0.2;
    private static final double KNOCKBACK = 2.5;
    private static final long COOLDOWN_TICKS = 80L;

    @Override
    public String id() {
        return "wardenwrath";
    }

    @Override
    public AffixType type() {
        // 2026-10-12 用户裁定：PASSIVE → RANGE（周期轮询，概率/冷却照旧）
        return AffixType.RANGE;
    }

    @Override
    public long cooldownTicks() {
        return COOLDOWN_TICKS;
    }

    /** ★ 原版 skills.WardenWrath  .chance 的值，照抄。 */
    @Override
    public double chance() {
        return 0.25;
    }


    @Override
    public void onTrigger(AffixContext ctx) {
        LivingEntity holder = ctx.entity();
        if (holder == null || !holder.isValid() || !ctx.hasValidTarget()) {
            return;
        }
        LivingEntity target = ctx.target();
        double distance = holder.getLocation().distance(target.getLocation());
        if (distance > MAX_RANGE) {
            return;
        }
        // 距离衰减：贴脸满伤，边缘只剩 20%
        double decay = 1.0 - (distance / MAX_RANGE) * (1.0 - MIN_MULTIPLIER);

        // 无来源伤害：避免原版再叠一次击退（见类注释 ③）
        double damage = BASE_DAMAGE * decay;
        if (damage > 0.01) {
            target.damage(damage);
        }
        // 手工击退：从怪指向目标，水平方向
        Vector dir = holder.getLocation().toVector().subtract(target.getLocation().toVector());
        dir.setY(0);
        if (dir.lengthSquared() > 1.0e-6) {
            dir.normalize();
            target.knockback(KNOCKBACK * decay, dir.getX(), dir.getZ());
        }

        // 视觉：从怪眼到目标的声波射线
        Location from = holder.getEyeLocation();
        Location to = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        AffixFx.line(from, to, Particle.SONIC_BOOM, 20);
        AffixFx.sound(from, "ENTITY_WARDEN_SONIC_BOOM", 1.5f, 1.0f);
    }
}
