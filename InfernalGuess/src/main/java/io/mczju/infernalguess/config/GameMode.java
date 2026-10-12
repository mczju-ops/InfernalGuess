package io.mczju.infernalguess.config;

/** 游戏模式。 */
public enum GameMode {
    /** 资金模式：有资金，按资金排名，允许并列胜。 */
    BASIC,
    /** 终极模式：**无资金概念**（Q48），只有生命与护盾，不接受并列胜。 */
    ULTIMATE;

    /** 是否有资金概念（决定记分板显示资金还是 ♥/✚）。 */
    public boolean usesFunds() {
        return this == BASIC;
    }

    /**
     * 下注时**是否需要选择金额**。
     *
     * <p>终极模式的亏损量固定为 1 点（生命或护盾），与押注比例**完全无关** ——
     * 所以再让玩家选 25% / 50% / ALL IN 是**误导**（选择不影响任何结果）。
     * 终极模式下选完阵营即完成下注（Q52）。
     */
    public boolean needsStakeChoice() {
        return this == BASIC;
    }

    /** 是否需要展示斩杀线（终极模式无资金，斩杀线无意义）。 */
    public boolean usesKillLine() {
        return this == BASIC;
    }

    /**
     * 是否允许**观望**。
     *
     * <p>终极模式**必须禁止**（用户第二轮反馈）：观望不承担任何风险、也不掉血，
     * 于是「一直观望」就是严格最优解 —— 这个模式会立刻失去玩法。
     * 所以终极模式只有「押左 / 押右」两个选项。
     */
    public boolean allowsWatch() {
        return this == BASIC;
    }
}
