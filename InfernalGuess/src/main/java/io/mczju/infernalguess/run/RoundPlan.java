package io.mczju.infernalguess.run;

import io.mczju.infernalguess.config.MobGroup;

import java.util.List;
import java.util.StringJoiner;

/**
 * 单个回合的出战计划（不可变）。
 *
 * <p>本类型**不含任何 Bukkit 类型**，因此可以脱离服务器做单元测试与回归验证。
 */
public record RoundPlan(int round, SidePlan left, SidePlan right) {

    public RoundPlan {
        if (round < 1) {
            throw new IllegalArgumentException("round 必须 >= 1，实际 " + round);
        }
    }

    /** 一方（左/右）的阵容。 */
    public record SidePlan(List<RosterEntry> entries, double exactWeight, int roundedWeight) {
        public SidePlan {
            entries = List.copyOf(entries);
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    /**
     * 一组出战怪物。
     *
     * @param point   刷怪点位号
     * @param group   怪物组
     * @param amount  本组数量
     * @param affixes 本组命中的炒鸡词条（**整组共享**）
     * @param weight  本组权重 = 单怪权重 × 数量 × Π(1+词条加成)，**未取整**
     */
    public record RosterEntry(int point, MobGroup group, int amount,
                              List<MobGroup.Affix> affixes, double weight) {
        public RosterEntry {
            affixes = List.copyOf(affixes);
        }

        /** 人类可读：`僵尸×3 [剧毒]` */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(group.nameOrId()).append('×').append(amount);
            if (!affixes.isEmpty()) {
                StringJoiner sj = new StringJoiner("、");
                for (MobGroup.Affix a : affixes) {
                    sj.add(a.id());
                }
                sb.append(" [").append(sj).append(']');
            }
            return sb.toString();
        }
    }

    /** 人类可读的阵容描述，用于聊天栏/日志。 */
    public String describeSide(SidePlan side) {
        if (side.isEmpty()) {
            return "（空）";
        }
        StringJoiner sj = new StringJoiner("、");
        for (RosterEntry e : side.entries()) {
            sj.add(e.describe());
        }
        return sj.toString();
    }

    /** 双方权重差（绝对值）。 */
    public double weightGap() {
        return Math.abs(left.exactWeight() - right.exactWeight());
    }

    /** 相对权重差（以较大者为分母）。 */
    public double relativeGap() {
        double max = Math.max(left.exactWeight(), right.exactWeight());
        return max <= 0.0 ? 0.0 : weightGap() / max;
    }

    /** 权重是否在容差内（±tolerance）。 */
    public boolean withinTolerance(double tolerance) {
        return relativeGap() <= tolerance;
    }
}
