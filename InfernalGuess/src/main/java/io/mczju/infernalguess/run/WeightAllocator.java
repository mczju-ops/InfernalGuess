package io.mczju.infernalguess.run;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.DoubleSupplier;

/**
 * ★ 权重分配器 —— **纯函数**：给定抽到的组的单位权重与回合标准，求各组的数量。
 *
 * <h2>本次重写要解决的问题</h2>
 * 旧设计用 {@code wave-pools.yml} 按「出生点 × 回合区间」圈定候选池，难度靠池子控制。
 * 新设计只保留**一个大池** + **一张回合→权重换算表**：随机抽若干种怪，
 * 再**按权重决定每种的数量（可为 0）**，使总权重达到当前回合标准。
 *
 * <p>数学形式：给定 {@code w1..wk} 与目标 {@code T}，求整数 {@code n_i ≥ 0}，
 * 使 {@code |Σ n_i·w_i − T|} 最小。
 *
 * <h2>为什么暴力枚举就够</h2>
 * 单组上界 {@code cap_i = ceil(T/w_i)}；最坏情形 {@code T=25, w=0.5 → 51}
 * → 3 组约 13 万次浮点乘加，**单次 < 1 ms**；每回合只算 2 次（左右各一）。
 * 不需要动态规划，因此也就不需要一个复杂到难以验证的算法。
 *
 * <h2>并列裁决（顺序即优先级）</h2>
 * <ol>
 *   <li>{@code |Σ − T|} 最小 —— 满足「总权重达到标准」</li>
 *   <li><b>非零组数最多</b> —— 「数量可为 0」应该是**最后手段**，
 *       优先让抽到的怪都出场（否则会出现某个刷怪点空着、只有 2 种怪出场）</li>
 *   <li><b>单位总数最少</b> —— 同权重下偏爱精英（少实体 = 少卡顿、少视觉噪音）</li>
 *   <li>种子派生的随机 —— 保证**同 seed 完全可复现**</li>
 * </ol>
 * 第 4 条用**蓄水池抽样**实现（一趟扫描、内存 O(1)），因此整个分配过程是
 * 「先确定最优键，再在并列者中按种子随机挑一个」。
 *
 * <p>本类是纯 Java（不碰 Bukkit），所以可以被单测完整覆盖 —— 这正是把它单独拿出来的原因。
 */
public final class WeightAllocator {

    /**
     * 单组单位数的**硬上限**（用户定稿采用 32）。
     *
     * <p>为什么需要：末回合 {@code T=25} 若抽到 0.5 权重的组，按目标推导需要 50 个；
     * 32 是「不让屏幕被小体怪淹没」的安全线。被压住时**不会导致凑不够 T** ——
     * 因为目标是 3 个组的**和**，另外两组可以补足。
     */
    public static final int DEFAULT_MAX_UNITS_PER_GROUP = 32;

    /**
     * 兜底防爆：单组上限的绝对天花板，避免病态权重导致枚举爆炸。
     *
     * <p>{@code mob-groups.yml} 的 {@code amount.max} 也只允许到这个值 ——
     * 单一定义，避免「配置允许 500、分配器只认 64」这种两处数字漂移。
     */
    public static final int ABSOLUTE_MAX_UNITS_PER_GROUP = 64;

    private WeightAllocator() {
    }

    /** 一个分配结果。 */
    public record Allocation(int[] counts, double weight, double deviation) {

        /** 非零组的数量。 */
        public int nonZeroGroups() {
            int c = 0;
            for (int n : counts) {
                if (n > 0) {
                    c++;
                }
            }
            return c;
        }

        /** 单位总数。 */
        public int totalUnits() {
            int s = 0;
            for (int n : counts) {
                s += n;
            }
            return s;
        }

        public String describe() {
            return "counts=" + Arrays.toString(counts)
                    + " weight=" + String.format(Locale.ROOT, "%.2f", weight)
                    + " dev=" + String.format(Locale.ROOT, "%.3f", deviation);
        }
    }

    /**
     * ★ **抽取资格线**（用户定稿的「推导规则」，取代权重区间过滤配置表）。
     *
     * <p>单个单位必须能装进目标权重：{@code w ≤ T}。
     *
     * <p>为什么需要它：低回合（如 {@code T=3}）抽到高权重组（劫掠兽 {@code w=8}）时，
     * 只有 {@code n=0} 才可能满足容差 → 该组**注定成不了场**，白白占掉一个抽取名额，
     * 结果是这一侧只有 2 种怪出场、观感变差。在**抽取阶段**就排除它们即可，
     * 且这是**从 T 推导**出来的，不需要任何新配置。
     */
    public static boolean eligible(double unitWeight, double target) {
        return unitWeight > 0 && unitWeight <= target + 1e-9;
    }

    /**
     * ★ 全局数量下限规则（用户定稿）：**权重 &lt; 1 的组，数量下限 5**；其余为 0。
     *
     * <p>用意：0.5 权重的组（末影螨 / 蠹虫）如果只出 1~2 只，在场上几乎看不见、
     * 也不构成威胁；给它们一个下限才有存在感。
     */
    public static int defaultMinCount(double unitWeight) {
        return unitWeight < 1.0 ? 5 : 0;
    }

    /**
     * 求各组数量。
     *
     * @param weights        各组单位权重（长度 = 抽到的组数）
     * @param target         本回合的权重标准 T
     * @param minCounts      各组数量下限（可用 {@link #defaultMinCount} 构造）
     * @param maxCounts      各组数量硬上限（安全线；{@code null} 或 0 表示只受目标推导约束）
     * @param rng            种子派生的随机源（用于打破并列；{@code null} 表示取枚举中第一个）
     * @return 最优分配（{@code counts} 与目标权重 {@code weight}）
     */
    public static Allocation allocate(double[] weights, double target,
                                      int[] minCounts, int[] maxCounts,
                                      DoubleSupplier rng) {
        int k = weights.length;
        int[] min = new int[k];
        int[] cap = new int[k];
        for (int i = 0; i < k; i++) {
            min[i] = (minCounts != null && i < minCounts.length) ? Math.max(0, minCounts[i]) : 0;
            int hard = (maxCounts != null && i < maxCounts.length && maxCounts[i] > 0)
                    ? maxCounts[i] : Integer.MAX_VALUE;
            // 单组再多也只会离目标更远 → 上界由 T 推导（+1 是为了「略微超过」这一档也可选）
            int byTarget = weights[i] > 0 ? (int) Math.ceil(target / weights[i]) + 1 : 0;
            cap[i] = Math.max(min[i],
                    Math.min(Math.min(hard, ABSOLUTE_MAX_UNITS_PER_GROUP), byTarget));
        }
        Pick pick = new Pick();
        walk(weights, target, min, cap, 0, 0.0, 0, 0, new int[k], rng, pick);
        if (pick.counts == null) {
            // 理论上到不了这里（至少 n 全 0 是一个候选）；兜底返回全 0
            return new Allocation(new int[k], 0.0, target);
        }
        return new Allocation(pick.counts, pick.sum, Math.abs(pick.sum - target));
    }

    /**
     * ★ **软下限**：先按偏好下限求解；若因此离目标更远，则放宽下限重解，取偏差小者。
     *
     * <h2>为什么下限必须能放宽</h2>
     * 用户定稿的「权重 &lt; 1 的组数量下限 5」与「总权重必须达到当前回合的标准」
     * 在**低回合**会直接冲突：第 1 回合 {@code T=3} 若抽到洞穴蜘蛛（{@code w=0.8}），
     * 下限 5 已经把该组锁死在 4.0 权重上 —— 比目标还高，补什么只会更远（偏差 33%）。
     *
     * <p>两条规则不能同时满足时，以**权重达标**为准（那是用户的第一条需求，
     * 也直接决定左右两方是否公平）；下限退化为「能保则保」。
     *
     * <p>偏差相同时**保留下限解** —— 让弱小的怪成群出现是既定偏好。
     *
     * @param preferredMins 偏好下限（见 {@link #defaultMinCount}）
     */
    public static Allocation allocatePreferringMins(double[] weights, double target,
                                                    int[] preferredMins, int[] maxCounts,
                                                    DoubleSupplier rng) {
        Allocation strict = allocate(weights, target, preferredMins, maxCounts, rng);
        Allocation loose = allocate(weights, target, null, maxCounts, rng);
        return strict.deviation() <= loose.deviation() + 1e-9 ? strict : loose;
    }

    /** 是否落在允许的偏差比例内（超出时由调用方**只告警**，不失败）。 */
    public static boolean withinTolerance(Allocation a, double target, double toleranceRatio) {
        if (target <= 0) {
            return true;
        }
        return a.deviation() <= Math.max(1e-9, target * toleranceRatio);
    }

    // ---------------- 内部：暴力枚举 + 蓄水池抽样 ----------------

    private static void walk(double[] w, double target, int[] min, int[] cap, int i,
                             double sum, int nonZero, int units, int[] cur,
                             DoubleSupplier rng, Pick pick) {
        if (i == w.length) {
            pick.offer(cur, sum, nonZero, units, target, rng);
            return;
        }
        for (int n = min[i]; n <= cap[i]; n++) {
            cur[i] = n;
            walk(w, target, min, cap, i + 1, sum + n * w[i],
                    nonZero + (n > 0 ? 1 : 0), units + n, cur, rng, pick);
        }
        cur[i] = 0;
    }

    /** 维护「当前最优键」+ 在并列者中做蓄水池抽样。 */
    private static final class Pick {
        private double dev = Double.MAX_VALUE;
        private int nonZero = -1;
        private int units = Integer.MAX_VALUE;
        private int seen;
        private int[] counts;
        private double sum;

        void offer(int[] cur, double sum, int nonZero, int units, double target,
                   DoubleSupplier rng) {
            double d = Math.abs(sum - target);
            int cmp = compare(d, nonZero, units);
            if (cmp < 0) {
                this.dev = d;
                this.nonZero = nonZero;
                this.units = units;
                this.sum = sum;
                this.counts = cur.clone();
                this.seen = 1;
            } else if (cmp == 0) {
                this.seen++;
                // 蓄水池抽样：第 seen 个并列者以 1/seen 的概率替换
                if (rng != null && rng.getAsDouble() < 1.0 / this.seen) {
                    this.sum = sum;
                    this.counts = cur.clone();
                }
            }
        }

        /** 负数 = 左边的键更优。 */
        private int compare(double d, int nz, int u) {
            if (d < dev - 1e-9) {
                return -1;
            }
            if (d > dev + 1e-9) {
                return 1;
            }
            if (nz != nonZero) {
                return nz > nonZero ? -1 : 1;
            }
            if (u != units) {
                return u < units ? -1 : 1;
            }
            return 0;
        }
    }
}
