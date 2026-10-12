package io.mczju.infernalguess.betting;

import io.mczju.infernalguess.battle.BattleSide;
import io.mczju.infernalguess.config.Rounding;

import java.util.Locale;

/**
 * 一次下注。
 *
 * <p>注意：**比例 {@code 1.0} 就是 ALL IN** —— 与 {@code config.yml} 的
 * {@code bet-ratios: [0.25, 0.5, 1.0]} 一致，不需要额外的布尔标志。
 *
 * @param side  押注的阵营（{@code watch = true} 时无意义，可为 {@code null}）
 * @param ratio 押注比例，取值来自 {@code bet-ratios}
 * @param watch 是否观望
 */
public record Bet(BattleSide side, double ratio, boolean watch) {

    public static final Bet WATCH = new Bet(null, 0.0, true);

    public Bet {
        if (!watch) {
            if (side == null) {
                throw new IllegalArgumentException("非观望下注必须指定阵营");
            }
            if (ratio <= 0.0 || ratio > 1.0) {
                throw new IllegalArgumentException("押注比例应在 (0, 1]，实际 " + ratio);
            }
        }
    }

    /**
     * 观望。
     *
     * <p>⚠️ 方法名**不能**叫 {@code watch()} —— 记录组件 {@code watch} 的访问器已经占用了这个名字，
     * 声明同名静态方法会编译失败（实测踩过）。
     */
    public static Bet watching() {
        return WATCH;
    }

    public static Bet of(BattleSide side, double ratio) {
        return new Bet(side, ratio, false);
    }

    /** 是否 ALL IN（比例 1.0）。 */
    public boolean isAllIn() {
        return !watch && ratio >= 1.0;
    }

    /** 本次押注额；观望为 0。 */
    public long stake(long funds, Rounding rounding) {
        return watch ? 0L : rounding.apply(funds, ratio);
    }

    /** 显示用的比例标签。 */
    public String percentLabel() {
        if (watch) {
            return "观望";
        }
        if (isAllIn()) {
            return "ALL IN";
        }
        return String.format(Locale.ROOT, "%.0f%%", ratio * 100);
    }

    /** 记分板上用的短标签。 */
    public String shortLabel() {
        if (watch) {
            return "观";
        }
        if (isAllIn()) {
            return "ALL";
        }
        return String.format(Locale.ROOT, "%.0f%%", ratio * 100);
    }
}
