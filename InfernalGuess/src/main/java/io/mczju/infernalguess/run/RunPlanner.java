package io.mczju.infernalguess.run;

import io.mczju.infernalguess.config.GameConfig;
import io.mczju.infernalguess.config.MobGroup;
import io.mczju.infernalguess.config.MobGroupConfig;
import io.mczju.infernalguess.config.Rounding;
import io.mczju.infernalguess.run.RoundPlan.SidePlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 剧本生成器（**纯函数，不碰任何 Bukkit API**）。
 *
 * <p>用户需求：「开局时随机生成种子，使用种子生成整个剧本」。
 * 本类只吃三个纯数据输入（种子 / 配置 / 怪物大池），产出 {@link RoundPlan}，
 * 因此可以在普通 JUnit 里断言「同种子 → 同剧本」。
 *
 * <h2>★ 本版是重写后的算法（取代旧的 wave-pools + 爬山配平）</h2>
 * <pre>
 *   1. 大池   ：**所有**怪物组同属一个池，没有出生点/回合区间的区隔
 *   2. 资格线 ：单只权重 w ≤ 本回合目标 T 的组才可抽（从 T 推导，无需配置表）
 *   3. 抽 3 种：每方从合格池里**均匀随机**抽 {@value #ROSTER_POINTS} 个**互不相同**的组，
 *               分别落到该方的 1/2/3 号刷怪点位（每个点位只出一种怪）
 *   4. 摇词条 ：**先**摇词条 —— 它乘进权重，必须赶在算数量之前
 *   5. 定数量 ：{@link WeightAllocator} 一次算出各组数量（**可以为 0**），
 *               使该方总权重达到 T；数量为 0 的组不写进剧本（点位空着）
 * </pre>
 *
 * <p>两侧**各自独立**抽签，因此**允许抽出同一套阵容**（用户明确纠正过
 * 「镜像对局很常见」这一说法并不成立，所以不做强制区分）。
 * 同侧 3 种互不相同（用户定稿）。
 *
 * <h2>权重规则（用户第 8 条 + Q47）</h2>
 * <pre>
 *   组权重     = 单怪权重 × 数量 × Π(1 + 命中的词条加成)   ← 全程 double，不中途取整
 *   单方总权重 = Σ 组权重
 *   最终       = 只在【最后一步】四舍五入
 * </pre>
 * 关键性质：组权重对「数量」是**线性**的（见 {@link #groupWeight}），
 * 所以「单位权重」就是数量 = 1 时的取值 —— 分配器据此工作。
 *
 * <h2>词条规则（Q46 / Q49）</h2>
 * <pre>
 *   N = affixCountByRound[round-1]      // 该回合一组最多能命中几条
 *   N == 0 → 完全不生成词条（也不走炒鸡流程）
 *   N >  0 → 按【配置顺序】逐条掷 chance，命中者取前 N 条；整组共享同一批词条
 * </pre>
 *
 * <h2>确定性</h2>
 * 所有随机都从 {@code root} 按「回合 salt → 侧 salt → 第 i 种怪 salt」分层派生。
 * 每个决策点一条独立子序列，使得增删某一回合不扰动其它回合。
 */
public final class RunPlanner {

    /** 每方抽取的怪物种类数（= 每侧的刷怪点位数）。 */
    public static final int ROSTER_POINTS = 3;

    /**
     * 抽取的**重掷次数上限**（同一侧最多试这么多组三元组，取偏差最小的那组）。
     *
     * <h2>为什么需要重掷（实机发现）</h2>
     * 抽到的三种怪可能**在数学上凑不出目标**：第 1 回合目标 3，
     * 若三组都是权重 2（岩浆怪 / 幻翼 / 熊猫…共 7 组），
     * 任意数量之和只能是 2 的倍数（2/4/6…），离目标最近也差 1.0（±33%）。
     * 实测踩到：`左 [4] 凋灵骷髅×1、熊猫×1 = 4.00`，而右侧精确 3.00 —— 两侧明显不等强。
     *
     * <p>重掷**不破坏可复现性**：每次尝试从本侧随机源按固定 salt 派生，
     * 且「达标即停 / 取偏差最小」都是确定性规则，同一 seed 结果完全一致。
     *
     * <p>代价：每侧最多算 {@value} 次分配（单次 < 1ms，且**达标即停** ——
     * 多数情况下第 1 次就命中），一整局 10 回合最坏约 640 次分配，实测可忽略。
     */
    private static final int DRAW_ATTEMPTS = 32;

    private static final long SALT_LEFT = 1L;
    private static final long SALT_RIGHT = 2L;

    private RunPlanner() {
    }

    // ================= 主入口 =================

    /**
     * 生成第 {@code round} 回合的出战计划。
     *
     * @param root  整局根随机源（由同一 seed 构造）
     * @param round 回合数（1-based）
     * @param pool  怪物**大池**（组 id 列表）；一般就是
     *              {@link MobGroupConfig#ids()}（全部怪物组）
     */
    public static RoundPlan planRound(SeededRng root, int round, GameConfig game,
                                      MobGroupConfig groups, List<String> pool) {
        final int target = game.weightForRound(round);
        final int maxAffix = game.affixCountForRound(round);
        final Rounding rounding = game.weightRounding();

        List<String> eligible = eligiblePool(pool, groups, target);
        if (eligible.isEmpty()) {
            throw new IllegalStateException("第 " + round + " 回合（目标权重 " + target
                    + "）没有任何合格怪物组 —— 池里最轻的单只怪都已超过回合目标；"
                    + "请调低 mob-groups.yml 的 weight，或调高 config.yml 的 weight-by-round。");
        }

        // 每个回合一条独立子序列 → 增删某回合不扰动其它回合
        SeededRng roundRng = root.derive(saltForRound(round));
        double tolerance = game.weightTolerance();
        List<Mutable> left = drawSide(roundRng.derive(SALT_LEFT), eligible, groups, target,
                maxAffix, tolerance);
        List<Mutable> right = drawSide(roundRng.derive(SALT_RIGHT), eligible, groups, target,
                maxAffix, tolerance);
        return new RoundPlan(round, toSidePlan(left, rounding), toSidePlan(right, rounding));
    }

    /**
     * ★ **抽取资格线**（用户定稿的推导规则，取代「权重区间过滤表」）：{@code w ≤ T}。
     *
     * <p>单只装不进目标权重的组，只有「数量 = 0」才可能满足容差 —— 它注定成不了场，
     * 却会白白占掉三个抽取名额之一，结果是该侧只有 2 种怪出场。
     * 在抽取阶段就排除掉，且这条线是**从 T 推导**出来的，不需要任何新配置。
     */
    public static List<String> eligiblePool(List<String> pool, MobGroupConfig groups, double target) {
        List<String> out = new ArrayList<>(pool.size());
        for (String id : pool) {
            MobGroup g = groups.get(id).orElseThrow(
                    () -> new IllegalStateException("怪物池引用了不存在的怪物组：" + id));
            if (WeightAllocator.eligible(g.weight(), target)) {
                out.add(id);
            }
        }
        return out;
    }

    // ---------------- 摇一侧 ----------------

    /** 中间态：数量稍后由分配器统一决定。 */
    private static final class Mutable {
        /** 刷怪点位号（**1-based**，与竞技场绑定一致）。 */
        final int point;
        final MobGroup group;
        final List<MobGroup.Affix> affixes;
        int amount;

        Mutable(int point, MobGroup group, List<MobGroup.Affix> affixes, int amount) {
            this.point = point;
            this.group = group;
            this.affixes = affixes;
            this.amount = amount;
        }

        double weight() {
            return groupWeight(group, amount, affixes);
        }
    }

    /**
     * 摇一方的阵容：最多重掷 {@value #DRAW_ATTEMPTS} 次，取「离目标最近」的那组。
     *
     * <p>见 {@link #DRAW_ATTEMPTS} 的说明 —— 抽到的三元组可能凑不出目标权重，
     * 此时换一组再试，而不是硬着头皮让一侧偏 33%。
     */
    private static List<Mutable> drawSide(SeededRng sideRng, List<String> eligible,
                                          MobGroupConfig groups, int target, int maxAffix,
                                          double tolerance) {
        double accept = Math.max(1e-9, target * tolerance);
        List<Mutable> best = null;
        double bestDev = Double.MAX_VALUE;
        for (int attempt = 0; attempt < DRAW_ATTEMPTS; attempt++) {
            List<Mutable> candidate = drawOnce(sideRng.derive(attempt * 104_729L),
                    eligible, groups, target, maxAffix);
            double dev = Math.abs(total(candidate) - target);
            if (dev < bestDev - 1e-9) {
                bestDev = dev;
                best = candidate;
            }
            if (dev <= accept) {
                break; // 已达容差，不必再掷
            }
        }
        return best == null ? List.of() : best;
    }

    /** 摇一次：抽 {@value #ROSTER_POINTS} 个**互不相同**的组 → 先摇词条 → 再定数量。 */
    private static List<Mutable> drawOnce(SeededRng sideRng, List<String> eligible,
                                          MobGroupConfig groups, int target, int maxAffix) {
        List<Mutable> entries = new ArrayList<>(ROSTER_POINTS);
        Set<String> used = new HashSet<>();
        for (int i = 0; i < ROSTER_POINTS; i++) {
            List<String> avail = new ArrayList<>(eligible.size());
            for (String id : eligible) {
                if (!used.contains(id)) {
                    avail.add(id);
                }
            }
            if (avail.isEmpty()) {
                break; // 合格候选不够 3 个 → 少出几种，不硬凑
            }
            MobGroup g = pickGroup(avail, groups, sideRng.derive(i * 100L + 1L));
            used.add(g.id());
            // ★ 先摇词条：词条加成会乘进权重，必须赶在算数量之前
            List<MobGroup.Affix> affixes = rollAffixes(g, maxAffix, sideRng.derive(i * 100L + 3L));
            entries.add(new Mutable(i + 1, g, affixes, 0));
        }
        if (entries.isEmpty()) {
            return entries;
        }

        // 用分配器一次算出「每种几个」—— 目标是**整方**的权重标准
        double[] unit = new double[entries.size()];
        int[] preferredMins = new int[entries.size()];
        int[] caps = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Mutable m = entries.get(i);
            // groupWeight 对 amount 线性 → 单位权重就是 amount = 1 的取值
            unit[i] = groupWeight(m.group, 1, m.affixes);
            preferredMins[i] = WeightAllocator.defaultMinCount(unit[i]);
            // ★ 数量上限：配置的**安全上限**（缺省 32）与全局天花板取小
            caps[i] = Math.min(m.group.safetyCap(), WeightAllocator.DEFAULT_MAX_UNITS_PER_GROUP);
        }
        WeightAllocator.Allocation alloc = WeightAllocator.allocatePreferringMins(
                unit, target, preferredMins, caps, sideRng::nextDouble);
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).amount = alloc.counts()[i];
        }
        return entries;
    }

    /**
     * 把中间态折算成总权重（数量为 0 的组贡献 0）。
     *
     * <p>同侧互不相同（用户定稿）；两侧之间**允许**相同 —— 去重只在本侧内做。
     */
    private static double total(List<Mutable> side) {
        double s = 0.0;
        for (Mutable m : side) {
            s += m.weight();
        }
        return s;
    }

    /** 在候选里**均匀**随机取一个组。 */
    private static MobGroup pickGroup(List<String> candidates, MobGroupConfig groups, SeededRng rng) {
        String id = candidates.get(rng.nextInt(candidates.size()));
        return groups.get(id).orElseThrow(
                () -> new IllegalStateException("怪物池引用了不存在的怪物组：" + id));
    }

    /**
     * 掷本组词条 —— ★ **2026-10-12 重写：从全局池里不放回地抽 0..N 条**。
     *
     * <h2>旧做法与它的问题</h2>
     * 旧版按 {@code mob-groups.yml} 里该组自己的清单**逐条掷 chance**，
     * 于是「只有少数生物能抽到词条」（僵尸永远只有剧毒+复生）。
     *
     * <h2>现在的做法（用户裁定）</h2>
     * <ol>
     *   <li>先按 {@code maxAffix} 抽一个**条数 0..N**（三角分布，偏向中间）；
     *       {@code maxAffix == 0} 直接空手 —— 对应「该回合不生成词条」。</li>
     *   <li>再从本组**合法**的词条池里**不放回**地抽那么多条
     *       —— 抽中即生效，**不再掷 chance**（用户原话：「抽中了直接加进来」）。</li>
     *   <li>权重加成由 {@code AffixCatalog} 的分级表决定，不再逐组配置。</li>
     * </ol>
     *
     * <h2>为什么用「洗牌取前 k 条」而不是「逐条掷概率」</h2>
     * 逐条掷概率时，只要前面几条先命中就填满了名额，**配置表末尾的词条
     * 几乎永远抽不到**（plastic、armoured+ 等）。洗牌保证每条被抽中的
     * 边际概率完全相同，同时仍是**确定性**的（同一 seed → 同一结果）。
     */
    private static List<MobGroup.Affix> rollAffixes(MobGroup group, int maxAffix, SeededRng rng) {
        if (maxAffix <= 0) {
            return List.of();
        }
        List<String> legal = group.legalAffixIds();
        if (legal.isEmpty()) {
            return List.of();
        }
        int count = drawAffixCount(maxAffix, rng);
        if (count <= 0) {
            return List.of();
        }
        count = Math.min(count, legal.size());

        // 洗牌取前 k 条：Fisher-Yates，只用本决策点的独立子序列
        List<String> shuffled = new ArrayList<>(legal);
        for (int i = shuffled.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            if (i != j) {
                String tmp = shuffled.get(i);
                shuffled.set(i, shuffled.get(j));
                shuffled.set(j, tmp);
            }
        }
        List<MobGroup.Affix> hit = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = shuffled.get(i);
            io.mczju.infernalguess.affix.AffixCatalog.Def def =
                    io.mczju.infernalguess.affix.AffixCatalog.get(id);
            if (def == null) {
                continue;   // 理论上不可能：legalAffixIds 就来自目录
            }
            hit.add(new MobGroup.Affix(id, def.bonus()));
        }
        return List.copyOf(hit);
    }

    /**
     * 抽「本组命中几条词条」：{@code 0..maxAffix} 上的**三角分布**（偏向中间）。
     *
     * <p>为什么不用均匀分布：均匀时「0 条」和「满额」一样常见，
     * 观感上会频繁出现「这一组完全没词条」；三角分布让中间值更常见，
     * 同时**两端都保留概率**（既不是保底也不是封顶），与旧表
     * 「N = 该回合最多能命中几条」的语义一致。
     */
    private static int drawAffixCount(int maxAffix, SeededRng rng) {
        int n = maxAffix + 1;
        double[] w = new double[n];
        for (int k = 0; k < n; k++) {
            w[k] = Math.min(k + 1, n - k);   // 0..N 上先增后减 → 三角
        }
        return rng.weightedIndex(w);
    }

    /**
     * 中间态 → 不可变剧本。
     *
     * <p>数量为 0 的组**不写进剧本**：那意味着该点位本回合空着，
     * 界面与日志里显示「某怪 ×0」只会造成困惑。
     */
    private static SidePlan toSidePlan(List<Mutable> entries, Rounding rounding) {
        List<RoundPlan.RosterEntry> out = new ArrayList<>(entries.size());
        double total = 0.0;
        for (Mutable m : entries) {
            if (m.amount <= 0) {
                continue;
            }
            double w = m.weight();
            total += w;
            out.add(new RoundPlan.RosterEntry(m.point, m.group, m.amount, m.affixes, w));
        }
        return new RoundPlan.SidePlan(out, total, (int) rounding.roundValue(total));
    }

    /**
     * 组权重 = 单怪权重 × 数量 × Π(1 + 加成)。
     * **不取整**（Q47：只在最后一步四舍五入），且对 {@code amount} 线性。
     */
    public static double groupWeight(MobGroup group, int amount, List<MobGroup.Affix> affixes) {
        double w = group.weight() * amount;
        for (MobGroup.Affix a : affixes) {
            w *= (1.0 + a.weightBonus());
        }
        return w;
    }

    /** 回合 salt：每回合一条独立子序列。 */
    public static long saltForRound(int round) {
        return round * 1_000_000L;
    }

    // ================= 可达性探测（启动期自检）=================

    /**
     * 一个回合的**可达性探测**结果。
     *
     * @param eligibleGroups 合格（{@code w ≤ T}）的**组数** —— 少于 3 就抽不满 3 种
     * @param bestDeviation  在**所有可能抽到的权重三元组**里能达到的最小绝对偏差
     * @param bestCounts     达到该偏差的一组数量示例（便于人肉核对）
     */
    public record Feasibility(int round, double target, int eligibleGroups, int tripleCount,
                              double bestDeviation, String bestCounts) {

        /** 最小偏差占目标的比例。 */
        public double ratio() {
            return target <= 0 ? 0.0 : bestDeviation / target;
        }
    }

    /**
     * ★ 逐回合探测「本回合最好能凑到多接近目标」。
     *
     * <h2>为什么能穷举</h2>
     * 「能不能凑出某个总权重」只取决于**单位权重**，而 52 个组只有 12 个不同的权重值
     * → 枚举「权重三元组」（含重复值的可重复性约束）只有几百种，
     * 比枚举 52 选 3（22100 种）便宜得多，且**与抽取随机无关**。
     *
     * <h2>口径（诚实说明）</h2>
     * <ul>
     *   <li>单个组的数量上限按 {@link WeightAllocator#DEFAULT_MAX_UNITS_PER_GROUP}（32）算，
     *       **不读** {@code amount.max} —— 配置的更小上限只会让结果更差，这里是**乐观口径**；</li>
     *   <li>不含词条加成（词条会乘进权重），所以这是「无词条时的最好情况」。</li>
     * </ul>
     *
     * <p>两者叠加的含义：探测结果**达标**不保证每一局都达标，但探测结果**不达标**
     * 就一定是配置层面的问题（目标权重表与怪物权重不匹配），值得告警。
     */
    public static List<Feasibility> probeFeasibility(GameConfig game, double[] unitWeights) {
        List<double[]> triples = weightTriples(unitWeights);
        List<Feasibility> out = new ArrayList<>(game.rounds());
        int cap = WeightAllocator.DEFAULT_MAX_UNITS_PER_GROUP;
        for (int r = 1; r <= game.rounds(); r++) {
            double target = game.weightForRound(r);
            int eligibleGroups = 0;
            for (double w : unitWeights) {
                if (WeightAllocator.eligible(w, target)) {
                    eligibleGroups++;
                }
            }
            double best = Double.MAX_VALUE;
            String bestCounts = "（无合格组合）";
            int usable = 0;
            for (double[] t : triples) {
                if (!WeightAllocator.eligible(t[0], target)
                        || !WeightAllocator.eligible(t[1], target)
                        || !WeightAllocator.eligible(t[2], target)) {
                    continue;
                }
                usable++;
                int[] mins = {
                        WeightAllocator.defaultMinCount(t[0]),
                        WeightAllocator.defaultMinCount(t[1]),
                        WeightAllocator.defaultMinCount(t[2])};
                int[] caps = {cap, cap, cap};
                WeightAllocator.Allocation a = WeightAllocator.allocatePreferringMins(
                        t, target, mins, caps, null);
                if (a.deviation() < best - 1e-9) {
                    best = a.deviation();
                    bestCounts = a.describe();
                }
            }
            if (best == Double.MAX_VALUE) {
                best = target; // 一个组合都没有 → 偏差等价于「完全够不着」
            }
            out.add(new Feasibility(r, target, eligibleGroups, usable, best, bestCounts));
        }
        return out;
    }

    /**
     * 列出所有**可能同时抽到**的权重三元组。
     *
     * <p>用「不同的权重值 + 该值的组数」枚举，而不是枚举组 id：
     * 于是三元组允许出现重复权重，但只能重复到「真的有那么多组」为止。
     */
    private static List<double[]> weightTriples(double[] unitWeights) {
        TreeMap<Double, Integer> mult = new TreeMap<>();
        for (double w : unitWeights) {
            if (w > 0) {
                mult.merge(w, 1, Integer::sum);
            }
        }
        Double[] vals = mult.keySet().toArray(new Double[0]);
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < vals.length; i++) {
            for (int j = i; j < vals.length; j++) {
                for (int k = j; k < vals.length; k++) {
                    if (enoughGroups(mult, vals, i, j, k)) {
                        out.add(new double[]{vals[i], vals[j], vals[k]});
                    }
                }
            }
        }
        return out;
    }

    /** 池子里是否真的有足够多的**不同组**来同时凑出这三个权重。 */
    private static boolean enoughGroups(TreeMap<Double, Integer> mult, Double[] vals, int... idx) {
        Map<Double, Integer> need = new HashMap<>();
        for (int x : idx) {
            need.merge(vals[x], 1, Integer::sum);
        }
        for (Map.Entry<Double, Integer> e : need.entrySet()) {
            if (mult.getOrDefault(e.getKey(), 0) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** 人类可读的探测表（启动期日志用）。 */
    public static String describeFeasibility(List<Feasibility> rows, double tolerance, int poolSize) {
        StringBuilder sb = new StringBuilder();
        for (Feasibility f : rows) {
            boolean ok = f.ratio() <= tolerance;
            sb.append(String.format(Locale.ROOT,
                    "  R%-2d 目标 %-5.1f ｜ 可选 %2d/%-2d 组（%2d 种权重组合）｜ 最佳偏差 %5.2f%% %s%n",
                    f.round(), f.target(), f.eligibleGroups(), poolSize, f.tripleCount(),
                    f.ratio() * 100, ok ? "" : "  ← ⚠️ 超出容差"));
        }
        return sb.toString();
    }
}
