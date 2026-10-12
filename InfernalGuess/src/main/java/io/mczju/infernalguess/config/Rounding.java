package io.mczju.infernalguess.config;

/** 下注金额出现小数时的取整方式。 */
public enum Rounding {
    /** 向下取整（默认，🔵 Q2）。 */
    FLOOR,
    /** 四舍五入。 */
    ROUND,
    /** 向上取整。 */
    CEIL;

    /** 对「资金 × 比例」的结果取整，并夹到 [0, funds]。 */
    public long apply(long funds, double ratio) {
        return Math.max(0L, Math.min(funds, roundValue(funds * ratio)));
    }

    /**
     * 对任意数值取整。
     *
     * <p>用于**组权重 / 单方总权重**：用户 Q47 要求「计算全程用小数，
     * **只在最后一步**四舍五入」，因此调用方务必只在最终结果上调用本方法。
     */
    public long roundValue(double v) {
        return switch (this) {
            case FLOOR -> (long) Math.floor(v);
            case ROUND -> Math.round(v);
            case CEIL -> (long) Math.ceil(v);
        };
    }
}
