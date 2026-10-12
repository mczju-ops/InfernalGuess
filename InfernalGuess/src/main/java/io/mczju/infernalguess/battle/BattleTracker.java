package io.mczju.infernalguess.battle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 一个回合内的**单位追踪表**。
 *
 * <p>核心职责（对应 [docs/02](../../../../../../docs/02-玩法与数值.md) §6.1 的「计数单位 / 非计数单位」）：
 * <ul>
 *   <li>{@code counted = true} —— **计数单位**：阵容直接产出的本体怪，**计入「一方全灭」判定**</li>
 *   <li>{@code counted = false} —— **非计数单位**：战斗中衍生的召唤物（如**唤魔者召唤的恼鬼**），
 *       参战、但不计入存活判定</li>
 * </ul>
 *
 * <p>用户原话：
 * 「如果唤魔者的阵营只剩下恼鬼存活依然算作失败（恼鬼会攻击敌人，但不算做阵营存活单位）」
 */
public final class BattleTracker {

    /** 被追踪的单位。 */
    public record TrackedUnit(UUID uuid, BattleSide side, boolean counted) {
    }

    private final Map<UUID, TrackedUnit> units = new LinkedHashMap<>();

    /** 登记一个单位。 */
    public void track(UUID uuid, BattleSide side, boolean counted) {
        units.put(uuid, new TrackedUnit(uuid, side, counted));
    }

    public Optional<TrackedUnit> get(UUID uuid) {
        return Optional.ofNullable(units.get(uuid));
    }

    public boolean isTracked(UUID uuid) {
        return units.containsKey(uuid);
    }

    /**
     * 取消追踪。
     *
     * @return 该单位此前是否被追踪（**幂等**：重复调用返回 false，调用方据此避免重复计数）
     */
    public boolean untrack(UUID uuid) {
        return units.remove(uuid) != null;
    }

    /** 某方**仍存活的计数单位**数量 —— 「一方全灭」的唯一判据。 */
    public int countedAlive(BattleSide side) {
        int n = 0;
        for (TrackedUnit u : units.values()) {
            if (u.counted() && u.side() == side) {
                n++;
            }
        }
        return n;
    }

    /** 某方全部被追踪单位（含非计数）数量，用于日志与调试。 */
    public int totalAlive(BattleSide side) {
        int n = 0;
        for (TrackedUnit u : units.values()) {
            if (u.side() == side) {
                n++;
            }
        }
        return n;
    }

    /**
     * 某方**计数单位**的剩余总血量。
     *
     * <p>只统计计数单位 —— 与「一方全灭」的判据保持一致（恼鬼不该影响判定）。
     *
     * @param healthOf 单位 UUID → 当前血量；查不到（已卸载/不在区块）按 0 计
     */
    public double countedHealth(BattleSide side, java.util.function.ToDoubleFunction<UUID> healthOf) {
        double sum = 0;
        for (TrackedUnit u : units.values()) {
            if (u.counted() && u.side() == side) {
                sum += Math.max(0, healthOf.applyAsDouble(u.uuid()));
            }
        }
        return sum;
    }

    /**
     * **超时强制判定**（纯函数，可单元测试）。
     *
     * <p>时间耗尽时双方都还有存活单位，需要一个确定的裁决。判据链：
     * <ol>
     *   <li><b>计数存活数</b>多者胜</li>
     *   <li>相等 → <b>剩余总血量</b>多者胜（避免「1 只残血 = 1 只满血」）</li>
     *   <li>仍相等（真平局）→ 用 {@code coin} 裁决</li>
     * </ol>
     *
     * <p>⚠️ **{@code coin} 由本局种子派生**，不是 {@code Math.random()}：
     * 既保证不系统性偏袒任何一方，又保证同 seed 可复现（便于复盘与排障）。
     * 旧实现用 {@code left >= right ? LEFT : RIGHT} —— 平局永远判左方胜，是明确的偏袒。
     */
    public static BattleSide judgeOnTimeout(int leftCount, int rightCount,
                                            double leftHealth, double rightHealth,
                                            boolean coin) {
        if (leftCount != rightCount) {
            return leftCount > rightCount ? BattleSide.LEFT : BattleSide.RIGHT;
        }
        if (Math.abs(leftHealth - rightHealth) > 1e-6) {
            return leftHealth > rightHealth ? BattleSide.LEFT : BattleSide.RIGHT;
        }
        return coin ? BattleSide.LEFT : BattleSide.RIGHT;
    }

    /** 全部被追踪单位的快照（用于清场）。 */
    public List<TrackedUnit> all() {
        return new ArrayList<>(units.values());
    }

    public boolean isEmpty() {
        return units.isEmpty();
    }

    public int size() {
        return units.size();
    }

    public void clear() {
        units.clear();
    }

    /**
     * 「一方全灭」判定。
     *
     * @return 已分出胜负时返回胜方；否则返回 {@code null}
     */
    public BattleSide decideWinner() {
        boolean leftAlive = countedAlive(BattleSide.LEFT) > 0;
        boolean rightAlive = countedAlive(BattleSide.RIGHT) > 0;
        if (leftAlive && rightAlive) {
            return null;
        }
        if (!leftAlive && !rightAlive) {
            return null; // 双灭：视为平局，由调用方处理
        }
        return leftAlive ? BattleSide.LEFT : BattleSide.RIGHT;
    }
}
