package io.mczju.infernalguess.battle;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import io.mczju.infernalguess.InfernalGuessPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

import java.util.EnumSet;

/**
 * **坐骑导航 Goal** —— 让「载着骑手的坐骑」自己追向敌人。
 *
 * <h2>★ 为什么必须有它（这是骑乘组合能否成立的关键）</h2>
 * 原版有一条容易忽略的规则：**乘客的寻路不会移动载具** ——
 * 车辆由载具自己驱动。而我们的骑乘组合里，骑手（僵尸/骷髅/唤魔者…）
 * 靠 {@link EnemyTargetGoal} 索敌、再靠**自己的近战/远程 goal 带动移动**；
 * 但骑手是**乘客**，它那条寻路对载具毫无作用。
 *
 * <p>更麻烦的是：载具本身（鸡、马、蜘蛛、骆驼…）**没有攻击 goal**，
 * 所以即使给它 {@code setTarget}，它也只会**原地闲逛**。
 * 结果就是整组「**站着不动的活靶**」（docs/13 §10.3 已预警）。
 *
 * <p>本 Goal 就是补上这一环：用 Paper 的
 * {@link com.destroystokyo.paper.entity.Pathfinder#moveTo(LivingEntity, double)}
 * 真正驱动载具寻路。（已用 javap 核实：{@code Mob#getPathfinder()} 与
 * {@code Pathfinder#moveTo(LivingEntity, double)} 在 paper-api 上确实存在。）
 *
 * <h2>与 {@link EnemyTargetGoal} 的分工</h2>
 * <table border="1">
 *   <caption>两个 Goal 各管什么</caption>
 *   <tr><th></th><th>{@link EnemyTargetGoal}</th><th>本 Goal</th></tr>
 *   <tr><td>类型</td><td>{@code TARGET}</td><td>{@code MOVE}</td></tr>
 *   <tr><td>做什麼</td><td>{@code setTarget(...)}（谁是我的敌人）</td>
 *       <td>{@code getPathfinder().moveTo(...)}（怎么走过去）</td></tr>
 *   <tr><td>给谁挂</td><td>所有参战单位</td><td>**只有载具**（有乘客的怪）</td></tr>
 * </table>
 *
 * <p>⚠️ 载具只负责「把人送过去」，**不负责输出** —— 那是骑手的事。
 * 所以这里不 {@code setTarget}：让载具保持非攻击状态，避免它去抢骑手的仇恨。
 */
public final class MountDriveGoal implements Goal<Mob> {

    private static final GoalKey<Mob> KEY = GoalKey.of(Mob.class,
            new NamespacedKey(InfernalGuessPlugin.getInstance(), "mount_drive"));

    /** 寻路速度倍率。比原版闲逛快，但不至于跑得骑手打不着。 */
    private static final double SPEED = 1.25;

    /**
     * 靠到多近就停下。
     *
     * <p>2.5 格 ≈ 大多数生物的近战触及范围之上一点：
     * 停得太远骑手够不到，贴得太近会把敌人挤开、也会挡住骑手视线。
     */
    private static final double STOP_DISTANCE = 2.5;

    private final Mob mount;
    private final BattleSide mySide;
    private final EnemyTargetGoal.EnemyLocator locator;
    private final double range;

    public MountDriveGoal(Mob mount, BattleSide mySide,
                          EnemyTargetGoal.EnemyLocator locator, double range) {
        this.mount = mount;
        this.mySide = mySide;
        this.locator = locator;
        this.range = range;
    }

    @Override
    public boolean shouldActivate() {
        LivingEntity enemy = nearestEnemy();
        return enemy != null && distanceTo(enemy) > STOP_DISTANCE;
    }

    @Override
    public boolean shouldStayActive() {
        return nearestEnemy() != null;
    }

    @Override
    public void start() {
        drive();
    }

    @Override
    public void tick() {
        drive();
    }

    @Override
    public void stop() {
        try {
            mount.getPathfinder().stopPathfinding();
        } catch (Throwable ignored) {
            // 实体已卸载等 → 无事可做
        }
    }

    @Override
    public GoalKey<Mob> getKey() {
        return KEY;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE);
    }

    /** 供外部识别 / 去重（与 {@link EnemyTargetGoal#key()} 同一套做法）。 */
    public static GoalKey<Mob> key() {
        return KEY;
    }

    // ---------------- 内部 ----------------

    private void drive() {
        LivingEntity enemy = nearestEnemy();
        if (enemy == null) {
            return;
        }
        try {
            mount.getPathfinder().moveTo(enemy, SPEED);
        } catch (Throwable ignored) {
            // 寻路失败（目标不可达 / 实体异常）→ 下一 tick 自然重试
        }
    }

    private LivingEntity nearestEnemy() {
        return locator.findEnemy(mount, mySide, range);
    }

    private double distanceTo(LivingEntity enemy) {
        try {
            return mount.getLocation().distance(enemy.getLocation());
        } catch (Throwable t) {
            return 0.0;   // 跨世界等异常 → 当作「已到达」，不驱动
        }
    }
}
