package io.mczju.infernalguess.game;

import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.config.GameConfig;
import io.mczju.infernalguess.config.GameMode;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 侧边栏记分板。
 *
 * <h2>两套内容（用户第 8 条）</h2>
 * <ul>
 *   <li><b>资金模式</b>：按资金**降序**排列，每行显示「名次 · 名字 · 押注阵营 · 比例 · 资金」</li>
 *   <li><b>终极模式</b>：**不显示资金**（Q48），改为显示 ♥生命 / ✚护盾</li>
 * </ul>
 *
 * <p>为什么用**每人一块独立的 Scoreboard**：排名对所有人相同，但「你这局押了什么」
 * 因玩家而异。人数 ≤10，每人一块成本可忽略。
 *
 * <p>注意：这里用的是**新 Scoreboard 对象**（不是主计分板）—— 主计分板被
 * {@code BattleService} 用来放阵营影子队伍，两者不能混用。
 */
public final class ScoreboardService {

    /** 行数上限：侧边栏最多 15 行，标题 1 行。 */
    private static final int MAX_ROWS = 15;

    private final InfernalGuessPlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    public ScoreboardService(InfernalGuessPlugin plugin) {
        this.plugin = plugin;
    }

    /** 为玩家建立（或复用）记分板并挂上侧边栏。 */
    public Scoreboard boardFor(Player player) {
        return boards.computeIfAbsent(player.getUniqueId(), k -> {
            Scoreboard b = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = b.registerNewObjective("ig_sidebar", Criteria.DUMMY,
                    Component.text("斗蛐蛐"));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            player.setScoreboard(b);
            return b;
        });
    }

    /** 刷新所有真人玩家的侧边栏。 */
    public void update(RoundManager round) {
        for (GamePlayer gp : round.players()) {
            if (gp.isBot()) {
                continue;
            }
            Player p = Bukkit.getPlayer(gp.uuid());
            if (p != null && p.isOnline()) {
                update(round, p, gp);
            }
        }
    }

    /** 刷新某个玩家的侧边栏。 */
    public void update(RoundManager round, Player player, GamePlayer self) {
        Scoreboard board = boardFor(player);
        Objective obj = board.getObjective("ig_sidebar");
        if (obj == null) {
            obj = board.registerNewObjective("ig_sidebar", Criteria.DUMMY, Component.text("斗蛐蛐"));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        // 清掉旧行
        for (String entry : new ArrayList<>(board.getEntries())) {
            board.resetScores(entry);
        }

        GameConfig cfg = plugin.gameConfig();
        // ⚠️ 用「本局的模式」而不是当前配置 —— 否则调试指令改了配置会污染正在进行的对局显示
        boolean ultimate = round.mode() == GameMode.ULTIMATE;

        List<GamePlayer> ranked = new ArrayList<>(round.players());
        if (ultimate) {
            ranked.sort(Comparator.comparingInt(GamePlayer::life).reversed()
                    .thenComparing(Comparator.comparingInt(GamePlayer::shield).reversed()));
        } else {
            ranked.sort(Comparator.comparingLong(GamePlayer::funds).reversed());
        }

        int line = 0;
        // 头两行：回合 / 阶段
        add(obj, board, line++, "§e第 " + round.round() + " / " + cfg.rounds() + " 回合");
        String phaseText = switch (round.phase()) {
            case BETTING -> "§a下注中 " + Math.max(0, round.secondsLeft()) + "s";
            case FIGHTING -> "§c战斗中";
            case SETTLING, EFFECT -> "§6结算中";
            case CLEANUP -> "§7清场中";
            case ENDED -> "§7已结束";
            default -> "§7准备中";
        };
        add(obj, board, line++, phaseText);
        add(obj, board, line++, "");

        for (GamePlayer gp : ranked) {
            if (line >= MAX_ROWS - 2) {
                break;
            }
            // ★ 用户第二轮反馈：押注标记用**红/蓝的【左】【右】**，不要灰色 [L]/[R]
            String bet = "";
            if (gp.isAlive()) {
                if (gp.bet().watch()) {
                    bet = " " + legacy("battle.bet-watch");
                } else {
                    boolean leftSide = gp.bet().side() == io.mczju.infernalguess.battle.BattleSide.LEFT;
                    String mark = legacy(leftSide ? "battle.bet-left" : "battle.bet-right");
                    // 终极模式没有金额概念，不显示百分比（用户反馈 5.2）
                    bet = " " + mark + (ultimate ? "" : " " + gp.bet().shortLabel());
                }
            }

            String value = ultimate
                    ? ("§c♥" + gp.life() + " §b✚" + gp.shield())
                    : ("§6" + gp.funds());
            String name = gp.uuid().equals(self.uuid()) ? "§f" + gp.name() : "§7" + gp.name();
            String dead = gp.isAlive() ? "" : " §8☠";

            // ★ 用户反馈 5.1：数值放**最前**，自然左对齐 ——
            //   默认字体不是等宽的，名字在前的话 ♥/✚ 列永远对不齐。
            add(obj, board, line++, value + " §r" + name + bet + dead);
        }

        add(obj, board, line++, "");
        add(obj, board, line, ultimate
                ? "§7护盾第 " + cfg.shieldUntilRound() + " 回合起失效"
                : "§7斩杀线 " + cfg.killLineForRound(round.round()));
    }

    /**
     * 把 {@code display.yml} 的文案渲染成**传统 § 颜色码**。
     *
     * <p>侧边栏的行是纯字符串（Bukkit 的 {@code Objective#getScore(String)} 只收 String），
     * 所以 MiniMessage 的 Component 要先序列化成 § 形式。
     */
    private String legacy(String displayPath) {
        if (!plugin.displayConfig().hasMessage(displayPath)) {
            return "";
        }
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection()
                .serialize(plugin.displayConfig().render(displayPath));
    }

    /**
     * 加一行。
     *
     * <p>Bukkit 的侧边栏靠「内容不重复」来区分行，所以重复文本要加不可见后缀。
     */
    private void add(Objective obj, Scoreboard board, int score, String text) {
        String entry = text;
        int guard = 0;
        while (board.getEntries().contains(entry) && guard < 64) {
            entry = entry + "§r";
            guard++;
        }
        obj.getScore(entry).setScore(-score);
    }

    /** 清掉某个玩家的记分板，恢复主计分板。 */
    public void clear(Player player) {
        boards.remove(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    /** 清掉所有。 */
    public void clearAll() {
        for (UUID id : new ArrayList<>(boards.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        boards.clear();
    }
}
