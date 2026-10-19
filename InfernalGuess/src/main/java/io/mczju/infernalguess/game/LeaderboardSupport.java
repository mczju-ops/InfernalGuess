package io.mczju.infernalguess.game;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 两个排行榜共用的**取数与名字渲染**。
 *
 * <h2>为什么不用 MGC 的 {@code PlayerDataLeaderboard}</h2>
 * {@code PlayerDataLeaderboard} 只能「反射读一个字段 → 一行一个数字」，
 * 而用户要的战绩榜一行里有**三个**数字（胜场 / 总场次 / 最高连胜），
 * 且要过滤掉「零战绩」的玩家（否则榜尾全是 0 胜的人）。
 * 所以这里自己取一份快照，排行榜类继承 {@code AbstractLeaderboard}，
 * 在 {@code renderLine} 里按**同一个显示名**回查快照。
 *
 * <p>名字格式刻意**照抄** MGC（{@code PlayerDataLeaderboard.fetchFromPlayerData}）：
 * OP 深红、普通绿色，这样和别的子插件的榜看起来是一套。
 */
final class LeaderboardSupport {

    private LeaderboardSupport() {
    }

    /**
     * 取一份「显示名 → 档案」快照。
     *
     * <p>⚠️ 名字拿不到的档案会被**跳过**（离线玩家第一次被 {@code getOfflinePlayer}
     * 解析时可能没有名字）—— 宁可少一行，也不要显示 UUID。
     */
    static Map<String, InfernalGuessPlayerData> snapshot() {
        Map<String, InfernalGuessPlayerData> out = new LinkedHashMap<>();
        for (InfernalGuessPlayerData data : MCZJUGameCore.getPlayerDataManager()
                .getAllPlayerData(InfernalGuessPlayerData.class)) {
            String id = data.getPlayerID();
            if (id == null || id.isBlank()) {
                continue;
            }
            OfflinePlayer player;
            try {
                player = Bukkit.getOfflinePlayer(UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                continue;   // 不是 UUID（理论上不该发生）
            }
            String name = player.getName();
            if (name == null) {
                continue;
            }
            out.put(displayName(player, name), data);
        }
        return out;
    }

    /** 与 MGC 同一套着色：OP 深红、普通绿色。 */
    static String displayName(OfflinePlayer player, String name) {
        return "<%s>%s".formatted(player.isOp() ? "dark_red" : "green", name);
    }

    /** 名次前缀，例如 {@code <yellow>1.</yellow>}。 */
    static String rank(int rank) {
        return "<yellow>" + rank + ".</yellow>";
    }
}
