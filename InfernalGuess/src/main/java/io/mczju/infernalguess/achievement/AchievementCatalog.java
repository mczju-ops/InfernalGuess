package io.mczju.infernalguess.achievement;

import com.github.mczjuops.mczjugamecore.achievement.AchievementDefinition;
import com.github.mczjuops.mczjugamecore.achievement.AchievementKey;
import io.mczju.infernalguess.config.GameConfig;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本插件的 6 个成就**唯一定义处**。
 *
 * <h2>为什么定义在代码里，而不是 {@code achievements.yml}</h2>
 * 其中「土块！」的描述必须写**当前配置下的资金上限**（出厂 11,120,000），
 * 而这个数由 {@code initial-funds} / {@code kill-line-by-round} / {@code rounds} 现算。
 * 写在 YAML 里要么硬编码（改配置就骗人），要么再引一套占位符机制。
 * 只有 6 条固定成就，代码定义更直接，且能被单测逐条钉住。
 *
 * <p>图标/名称/描述都是 MGC 成就界面直接渲染的**纯文本**（MGC 按纯文本解析）。
 */
public final class AchievementCatalog {

    /** MGC 成就的游戏命名空间（= 本插件游戏 id）。 */
    public static final String GAME_ID = "infernalguess";

    // ---------------- 成就 id（与 MGC 的 AchievementKey 组合使用）----------------
    public static final String BASIC_MAX_FUNDS = "basic_max_funds";
    public static final String ULTIMATE_ROUND_11 = "ultimate_round_11";
    public static final String BASIC_ALLIN_BUST_10 = "basic_allin_bust_10";
    public static final String BASIC_WATCH_ONLY_WIN = "basic_watch_only_win";
    public static final String ULTIMATE_CONTRARIAN_WIN = "ultimate_contrarian_win";
    public static final String WINS_100 = "wins_100";

    /** 「赌神」的目标胜场。 */
    public static final int WINS_TARGET = 100;

    /**
     * 成就定义的只读规格（供注册与测试）。
     *
     * <p>图标存**材质名**而不是 {@link Material}：离线单测里 `Material` 的静态初始化
     * 会访问 Paper 注册表（`No RegistryAccess`），而本类要被纯 JUnit 逐条钉住。
     * 真正的 Material 只在 {@link #definitions} 里解析（那一步只在服务端发生）。
     */
    public record Spec(String id, String name, String description, String iconKey,
                       boolean counter, long target) {
    }

    private AchievementCatalog() {
    }

    /** 当前配置下的资金模式理论上限（10 回合全 ALL IN 且全对 = 11,120,000）。 */
    public static long maxBasicFunds(GameConfig cfg) {
        if (cfg == null) {
            return 0L;
        }
        return AchievementRules.maxBasicFunds(cfg.initialFunds(), cfg.rounds(),
                r -> cfg.killLineForRound(r));
    }

    /** 6 条成就的规格（顺序 = MGC 成就界面里的顺序）。 */
    public static List<Spec> specs(GameConfig cfg) {
        long max = maxBasicFunds(cfg);
        String maxText = formatFunds(max);
        List<Spec> out = new ArrayList<>(6);
        out.add(new Spec(BASIC_MAX_FUNDS, "土块！",
                "在资金模式下单局获得 " + maxText + "资金",
                "DIRT", false, 0));
        out.add(new Spec(ULTIMATE_ROUND_11, "高端局",
                "在对决模式中存活至第 11 轮",
                "TOTEM_OF_UNDYING", false, 0));
        out.add(new Spec(BASIC_ALLIN_BUST_10, "老实赚钱，很枯燥吧？",
                "连续十次在资金模式中因为 ALL IN 输光全部资金",
                "PINK_WOOL", false, 0));
        out.add(new Spec(BASIC_WATCH_ONLY_WIN, "！？观者？！",
                "在资金模式中获胜，且整局只选择过“观望”选项",
                "PURPLE_WOOL", false, 0));
        out.add(new Spec(ULTIMATE_CONTRARIAN_WIN, "豪情在天",
                "对决模式中，选出与其他所有存活参与者全部相反的选项，并在这轮获胜",
                "END_CRYSTAL", false, 0));
        out.add(new Spec(WINS_100, "炒鸡蛐蛐王",
                "在《炒鸡斗蛐蛐》中累计胜出 " + WINS_TARGET + " 局",
                "GOLD_BLOCK", true, WINS_TARGET));
        return out;
    }

    /** 转成 MGC 的成就定义。 */
    public static List<AchievementDefinition> definitions(GameConfig cfg) {
        List<AchievementDefinition> out = new ArrayList<>();
        for (Spec s : specs(cfg)) {
            AchievementKey key = new AchievementKey(GAME_ID, s.id());
            Material icon = Material.matchMaterial(s.iconKey());
            if (icon == null) {
                icon = Material.STONE;   // 只可能是拼错；退回一个必定存在的图标
            }
            out.add(s.counter()
                    ? AchievementDefinition.counter(key, s.name(), s.description(),
                            icon, s.target())
                    : AchievementDefinition.challenge(key, s.name(), s.description(), icon));
        }
        return out;
    }

    /** 中文/英文都用逗号分组（`11,120,000`）。 */
    public static String formatFunds(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
