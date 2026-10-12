package io.mczju.infernalguess.run;

import java.util.Random;

/**
 * 确定性随机源。
 *
 * <p>用户需求：「开局时随机生成种子，**使用种子生成整个剧本**」。
 * 因此整局的所有随机决策（抽竞技场、抽怪物组、抽数量、掷词条）都必须走本类，
 * **不得**使用 {@code ThreadLocalRandom} 或 {@code Math.random()}。
 *
 * <p>关键设计（借鉴 Maggoteers 已验证实现，见 docs/04 §4.2）：
 * <ul>
 *   <li>每个决策点用一条**独立子序列**（{@link #derive}），
 *       使得「增删某一回合」不会扰动其它回合的随机结果；</li>
 *   <li>{@code derive} 派生的是**当前实例的 seed**，可链式调用。</li>
 * </ul>
 *
 * <p>⚠️ Maggoteers 的同类实现存在**注释与代码不一致**（注释写 {@code seed ^ salt}，
 * 实际是 {@code seed + salt * GOLDEN}）。这里以**代码**为准，并把行为写进文档。
 */
public final class SeededRng {

    /** 黄金比例常数（2^64 / φ），用于分散相邻 salt。 */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private final long seed;
    private final Random random;

    public SeededRng(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    /** 本实例的种子。 */
    public long seed() {
        return seed;
    }

    /** [0, bound) 的整数；{@code bound <= 0} 时返回 0。 */
    public int nextInt(int bound) {
        return bound <= 0 ? 0 : random.nextInt(bound);
    }

    /** [min, max] 的整数（含端点）。 */
    public int nextInt(int min, int max) {
        return max <= min ? min : min + random.nextInt(max - min + 1);
    }

    /** [0.0, 1.0) 的小数。 */
    public double nextDouble() {
        return random.nextDouble();
    }

    /**
     * 以概率 {@code p} 返回 {@code true}。
     * {@code p <= 0} 恒 false；{@code p >= 1} 恒 true。
     */
    public boolean chance(double p) {
        if (p <= 0.0) {
            return false;
        }
        if (p >= 1.0) {
            return true;
        }
        return random.nextDouble() < p;
    }

    /**
     * 按权重取下标。负权按 0 处理；总权为 0 时返回 0；
     * 浮点累加兜底返回最后一个下标。
     */
    public int weightedIndex(double[] weights) {
        if (weights == null || weights.length == 0) {
            return 0;
        }
        double total = 0.0;
        for (double w : weights) {
            if (w > 0) {
                total += w;
            }
        }
        if (total <= 0.0) {
            return 0;
        }
        double r = random.nextDouble() * total;
        double acc = 0.0;
        for (int i = 0; i < weights.length; i++) {
            double w = weights[i];
            if (w <= 0) {
                continue;
            }
            acc += w;
            if (r < acc) {
                return i;
            }
        }
        return weights.length - 1;
    }

    /**
     * 派生一条独立子序列。
     *
     * @param salt 盐值；同一父序列下**不同决策点必须用不同 salt**
     */
    public SeededRng derive(long salt) {
        return new SeededRng(mix64(seed + salt * GOLDEN));
    }

    /** murmur3 终结器（64 位）。 */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
