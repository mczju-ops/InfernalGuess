package io.mczju.infernalguess.battle;

import io.mczju.infernalguess.InfernalGuessPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;

import java.util.EnumSet;

/**
 * 自定义「敌对索敌」Goal —— [docs/07](../../../../../../docs/07-Q30-互相敌对实现调研.md) 的**推荐主方案**。
 *
 * <h2>为什么不用 {@code setTarget} 了事</h2>
 * 实测与文献（docs/07 §1）：{@code Mob#setTarget} 有效，但会被原版运行中的 target goal **重写**；
 * 而 Paper 提供了合法的 Mob Goal API：先
 * {@code getMobGoals().removeAllGoals(mob, GoalType.TARGET)} 清掉原版索敌，
 * 再挂本 Goal，之后**原版近战/远程攻击 goal 会自动接管输出**（保留原版战斗观感）。
 *
 * <h2>「一只怪对多个目标敌对」怎么理解</h2>
 * 原版 {@code Mob.target} 是**单值**，所以「对整方敌对」的正确含义是
 * **候选集合任意 + 当前目标会切换**（docs/07 §5.1）。
 * 本 Goal 每次 {@code tick} 都重新在**敌方阵营**里找最近的单位。
 *
 * <p>⚠️ brain 型生物（猪灵/疣猪兽/监守者等）没有 goal selector，
 * 本方案不适用 —— 已在 `mob-groups.yml` 默认排除。
 */
public final class EnemyTargetGoal implements Goal<Mob> {

    /** 供 Goal 查询「谁是敌人」的接口（由 {@link BattleService} 实现）。 */
    public interface EnemyLocator {
        /**
         * 在 {@code self} 周围 {@code range} 内找一个敌对单位。
         *
         * @param mySide 自己所属阵营
         * @return 找到的敌人；没有则 {@code null}
         */
        LivingEntity findEnemy(LivingEntity self, BattleSide mySide, double range);
    }

    private static final GoalKey<Mob> KEY = GoalKey.of(Mob.class,
            new NamespacedKey(InfernalGuessPlugin.getInstance(), "enemy_target"));

    private final Mob mob;
    private final BattleSide mySide;
    private final EnemyLocator locator;
    private final double range;

    public EnemyTargetGoal(Mob mob, BattleSide mySide, EnemyLocator locator, double range) {
        this.mob = mob;
        this.mySide = mySide;
        this.locator = locator;
        this.range = range;
    }

    @Override
    public boolean shouldActivate() {
        return isValidTarget(mob.getTarget()) || locator.findEnemy(mob, mySide, range) != null;
    }

    @Override
    public boolean shouldStayActive() {
        return isValidTarget(mob.getTarget());
    }

    @Override
    public void start() {
        LivingEntity enemy = locator.findEnemy(mob, mySide, range);
        mob.setTarget(enemy);
    }

    @Override
    public void tick() {
        // 目标失效（死亡/超距/**变成同阵营**）就立刻换一个
        if (!isValidTarget(mob.getTarget())) {
            mob.setTarget(locator.findEnemy(mob, mySide, range));
        }
    }

    @Override
    public void stop() {
        mob.setTarget(null);
    }

    @Override
    public GoalKey<Mob> getKey() {
        return KEY;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.TARGET);
    }

    /**
     * 当前目标是否仍然有效。
     *
     * <p>⚠️ **必须校验「是对面」**。早期版本只检查「活着 + 同世界 + 在射程内」，
     * 于是**任何来源的同阵营目标都会被本 Goal 一直维持下去** ——
     * 实测症状：「骷髅似乎会对同队的骷髅产生敌意」。
     *
     * <p>同阵营目标可能来自：
     * <ul>
     *   <li>原版某个没被 {@code removeAllGoals} 清掉的 goal（如 {@code HurtByTargetGoal}）</li>
     *   <li>开打前被队友误伤留下的 {@code lastHurtByMob}</li>
     *   <li>brain 型生物退化路径里的 {@code setTarget}</li>
     * </ul>
     * 不管来源如何，这里一律拒绝同阵营目标即可根治。
     */
    private boolean isValidTarget(LivingEntity target) {
        if (target == null || target.isDead() || !target.isValid()) {
            return false;
        }
        var targetSide = BattleService.sideOf(target);
        if (targetSide.isEmpty() || targetSide.get() == mySide) {
            return false; // 同阵营 / 未标记 → 不是敌人
        }
        if (!target.getWorld().equals(mob.getWorld())) {
            return false;
        }
        return target.getLocation().distanceSquared(mob.getLocation()) <= range * range;
    }

    /** 供外部识别本 Goal。 */
    public static GoalKey<Mob> key() {
        return KEY;
    }
}
