package io.mczju.infernalguess.command;

import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.config.ArenaConfig;
import io.mczju.infernalguess.config.ConfigError;
import io.mczju.infernalguess.config.DisplayConfig;
import io.mczju.infernalguess.config.GameConfig;
import io.mczju.infernalguess.config.Rounding;
import io.mczju.infernalguess.gui.ArenaEditMenu;
import io.mczju.infernalguess.run.RoundPlan;
import io.mczju.infernalguess.run.RunPlanner;
import io.mczju.infernalguess.run.SeededRng;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /ig} 主指令。
 *
 * <h2>玩家指令</h2>
 * {@code info} · {@code preview <seed> [round]} · {@code menu} · {@code difficulty} · {@code leave}
 *
 * <h2>管理员指令（{@code infernalguess.admin}）</h2>
 * {@code arena list|create|delete|rename|select|info|edit|setspawn|setpoint} ·
 * {@code debug reload|preview|summon|skip|set}
 *
 * <p>尚未接入对局的部分（{@code menu}/{@code difficulty}/{@code leave}/{@code summon}/{@code skip}/{@code set}）
 * 会明确提示「属于哪个阶段」，而不是静默无反应。
 */
public final class InfernalGuessCommand implements CommandExecutor, TabCompleter {

    private final InfernalGuessPlugin plugin;

    /** 每个管理员当前正在编辑的竞技场。 */
    private final Map<UUID, String> selectedArena = new HashMap<>();

    public InfernalGuessCommand(InfernalGuessPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            return switch (sub) {
                case "info" -> info(sender);
                case "preview" -> preview(sender, args, 1);
                case "menu" -> notYet(sender, "下注界面", "阶段 7");
                case "difficulty" -> notYet(sender, "难度选择", "阶段 5");
                case "leave" -> notYet(sender, "退出对局", "阶段 5");
                case "arena" -> arena(sender, args);
                case "mode" -> modeCmd(sender, modeArgs(args));
                case "debug" -> debug(sender, args);
                default -> {
                    help(sender);
                    yield true;
                }
            };
        } catch (ConfigError e) {
            sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            sender.sendMessage(Component.text("[IG] " + e.getMessage(), NamedTextColor.RED));
            return true;
        }
    }

    // ================= info / preview =================

    private boolean info(CommandSender sender) {
        GameConfig g = plugin.gameConfig();
        sender.sendMessage(Component.text("─── InfernalGuess ───", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("回合 " + g.rounds()
                + " ｜ 人数 " + g.minPlayers() + "~" + g.maxPlayers()
                + " ｜ 模式 " + g.mode()
                + " ｜ 初始资金 " + g.initialFunds(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("词条：" + (g.affixesEnabled() ? "启用" : "关闭")
                + "（已实现 " + plugin.affixRegistry().size() + " 个词条）"
                + " ｜ 怪物组 " + plugin.mobGroupConfig().size() + " 组"
                + " ｜ 竞技场 " + plugin.arenaConfig().arenas().size()
                + "（可用 " + plugin.arenaConfig().playable().size() + "）", NamedTextColor.GRAY));
        for (int r = 1; r <= g.rounds(); r++) {
            int eligible = RunPlanner.eligiblePool(
                    plugin.mobGroupConfig().ids(), plugin.mobGroupConfig(), g.weightForRound(r)).size();
            sender.sendMessage(Component.text("  第 " + r + " 回合：可选 " + eligible + "/"
                    + plugin.mobGroupConfig().size() + " 组"
                    + " ｜ 目标权重 " + g.weightForRound(r)
                    + " ｜ 斩杀线 " + g.killLineForRound(r)
                    + " ｜ 最多词条 " + g.affixCountForRound(r), NamedTextColor.DARK_GRAY));
        }
        return true;
    }

    /** {@code /ig preview <seed> [round]} —— 不依赖对局，直接把剧本打出来看。 */
    private boolean preview(CommandSender sender, String[] args, int seedIndex) {
        if (args.length <= seedIndex) {
            sender.sendMessage(Component.text("用法：/ig preview <seed> [round]", NamedTextColor.YELLOW));
            return true;
        }
        long seed;
        try {
            seed = Long.parseLong(args[seedIndex]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("seed 必须是数字", NamedTextColor.RED));
            return true;
        }
        GameConfig game = plugin.gameConfig();
        int only = -1;
        if (args.length > seedIndex + 1) {
            try {
                only = Integer.parseInt(args[seedIndex + 1]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("round 必须是数字", NamedTextColor.RED));
                return true;
            }
            if (only < 1 || only > game.rounds()) {
                sender.sendMessage(Component.text("round 应在 1~" + game.rounds(), NamedTextColor.RED));
                return true;
            }
        }

        SeededRng root = new SeededRng(seed);
        RoundPlan.SidePlan left = null;
        RoundPlan.SidePlan right = null;
        for (int r = 1; r <= game.rounds(); r++) {
            RoundPlan plan = RunPlanner.planRound(root, r, game,
                    plugin.mobGroupConfig(), plugin.mobGroupConfig().ids());
            if (only == -1 || only == r) {
                sender.sendMessage(Component.text("═══ 第 " + r + " 回合（目标权重 "
                        + game.weightForRound(r) + "）═══", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("左 " + left(plan) + "  → 权重 "
                        + round(plan.left().exactWeight()) + "（相对差 "
                        + pct(plan.relativeGap()) + "）", NamedTextColor.AQUA));
                sender.sendMessage(Component.text("   " + plan.describeSide(plan.left()), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("右 " + right(plan) + "  → 权重 "
                        + round(plan.right().exactWeight()), NamedTextColor.RED));
                sender.sendMessage(Component.text("   " + plan.describeSide(plan.right()), NamedTextColor.GRAY));
            }
            if (r == game.rounds()) {
                left = plan.left();
                right = plan.right();
            }
        }
        return true;
    }

    private static String left(RoundPlan p) {
        return "[" + p.left().roundedWeight() + "]";
    }

    private static String right(RoundPlan p) {
        return "[" + p.right().roundedWeight() + "]";
    }

    private static String round(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    // ================= /ig arena =================

    private boolean arena(CommandSender sender, String[] args) {
        if (!sender.hasPermission("infernalguess.admin")) {
            sender.sendMessage(plugin.displayConfig().render("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "用法：/ig arena <list|create|delete|rename|select|info|edit|setspawn|setpoint>",
                    NamedTextColor.YELLOW));
            return true;
        }
        ArenaConfig cfg = plugin.arenaConfig();
        String action = args[1].toLowerCase(Locale.ROOT);

        switch (action) {
            case "list" -> {
                if (cfg.isEmpty()) {
                    sender.sendMessage(Component.text("还没有任何竞技场。", NamedTextColor.GRAY));
                    return true;
                }
                StringBuilder sb = new StringBuilder();
                for (ArenaConfig.Arena a : cfg.arenas()) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(a.name()).append(a.isComplete() ? "" : "(不完整)");
                }
                sender.sendMessage(plugin.displayConfig().render("debug.arena-list",
                        Map.of("names", sb.toString())));
            }
            case "create" -> {
                if (args.length < 3) {
                    usage(sender, "/ig arena create <名称>");
                    return true;
                }
                String name = args[2];
                String world = plugin.gameConfig().arenaWorld();
                if (cfg.create(name, world).isEmpty()) {
                    sender.sendMessage(plugin.displayConfig().render("debug.arena-name-used",
                            Map.of("name", name)));
                    return true;
                }
                save(sender);
                select(sender, name);
                sender.sendMessage(plugin.displayConfig().render("debug.arena-created",
                        Map.of("name", name)));
                sender.sendMessage(Component.text("世界：" + world
                        + "。接着用 /ig arena edit " + name + " 打开调试 UI 绑定点位。", NamedTextColor.GRAY));
            }
            case "delete" -> {
                if (args.length < 3) {
                    usage(sender, "/ig arena delete <名称>");
                    return true;
                }
                if (!cfg.delete(args[2])) {
                    sender.sendMessage(plugin.displayConfig().render("debug.arena-missing",
                            Map.of("name", args[2])));
                    return true;
                }
                save(sender);
                sender.sendMessage(plugin.displayConfig().render("debug.arena-deleted",
                        Map.of("name", args[2])));
            }
            case "rename" -> {
                if (args.length < 4) {
                    usage(sender, "/ig arena rename <旧名> <新名>");
                    return true;
                }
                if (!cfg.rename(args[2], args[3])) {
                    sender.sendMessage(Component.text("重命名失败：旧名不存在或新名已被占用。",
                            NamedTextColor.RED));
                    return true;
                }
                save(sender);
                sender.sendMessage(plugin.displayConfig().render("debug.arena-renamed",
                        Map.of("old", args[2], "new", args[3])));
            }
            case "select" -> {
                if (args.length < 3) {
                    usage(sender, "/ig arena select <名称>");
                    return true;
                }
                if (cfg.get(args[2]).isEmpty()) {
                    sender.sendMessage(plugin.displayConfig().render("debug.arena-missing",
                            Map.of("name", args[2])));
                    return true;
                }
                select(sender, args[2]);
                sender.sendMessage(plugin.displayConfig().render("debug.arena-selected",
                        Map.of("name", args[2])));
            }
            case "info" -> arenaInfo(sender, args.length >= 3 ? args[2] : current(sender));
            case "edit" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.displayConfig().render("players-only"));
                    return true;
                }
                String name = args.length >= 3 ? args[2] : current(sender);
                if (name == null || cfg.get(name).isEmpty()) {
                    sender.sendMessage(Component.text(
                            "请先 /ig arena select <名称>，或 /ig arena edit <名称>。", NamedTextColor.YELLOW));
                    return true;
                }
                ArenaEditMenu.open(plugin, player, name);
            }
            case "setspawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.displayConfig().render("players-only"));
                    return true;
                }
                String name = current(sender);
                if (name == null) {
                    needSelect(sender);
                    return true;
                }
                if (args.length < 3) {
                    usage(sender, "/ig arena setspawn <player|spectate>");
                    return true;
                }
                Location loc = player.getLocation();
                ArenaConfig.Spawn spawn = new ArenaConfig.Spawn(
                        loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
                boolean ok = switch (args[2].toLowerCase(Locale.ROOT)) {
                    case "player" -> cfg.setPlayerSpawn(name, spawn);
                    case "spectate" -> cfg.setSpectateSpawn(name, spawn);
                    default -> {
                        usage(sender, "/ig arena setspawn <player|spectate>");
                        yield false;
                    }
                };
                if (ok) {
                    save(sender);
                    sender.sendMessage(plugin.displayConfig().render("debug.spawn-set", Map.of(
                            "kind", args[2].toLowerCase(Locale.ROOT).equals("player") ? "玩家传送点" : "观战席",
                            "loc", String.format(Locale.ROOT, "%.1f, %.1f, %.1f",
                                    loc.getX(), loc.getY(), loc.getZ()))));
                }
            }
            case "setpoint" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.displayConfig().render("players-only"));
                    return true;
                }
                String name = current(sender);
                if (name == null) {
                    needSelect(sender);
                    return true;
                }
                if (args.length < 4) {
                    usage(sender, "/ig arena setpoint <left|right> <1|2|3>");
                    return true;
                }
                boolean leftSide = args[2].toLowerCase(Locale.ROOT).startsWith("l");
                int idx;
                try {
                    idx = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    usage(sender, "/ig arena setpoint <left|right> <1|2|3>");
                    return true;
                }
                Location loc = player.getLocation();
                if (!cfg.setPoint(name, leftSide, idx,
                        new ArenaConfig.Point(loc.getX(), loc.getY(), loc.getZ()))) {
                    sender.sendMessage(Component.text("点位号应为 1~" + ArenaConfig.POINTS_PER_SIDE
                            + "，或竞技场不存在。", NamedTextColor.RED));
                    return true;
                }
                save(sender);
                sender.sendMessage(plugin.displayConfig().render("debug.point-set", Map.of(
                        "side", leftSide ? "左" : "右",
                        "point", String.valueOf(idx),
                        "loc", String.format(Locale.ROOT, "%.1f, %.1f, %.1f",
                                loc.getX(), loc.getY(), loc.getZ()))));
            }
            default -> usage(sender,
                    "/ig arena <list|create|delete|rename|select|info|edit|setspawn|setpoint>");
        }
        return true;
    }

    private void arenaInfo(CommandSender sender, String name) {
        if (name == null) {
            needSelect(sender);
            return;
        }
        ArenaConfig.Arena a = plugin.arenaConfig().get(name).orElse(null);
        if (a == null) {
            sender.sendMessage(plugin.displayConfig().render("debug.arena-missing", Map.of("name", name)));
            return;
        }
        sender.sendMessage(Component.text("─── 竞技场 " + a.name() + " ───", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("世界：" + a.world()
                + " ｜ " + (a.isComplete() ? "完整（可开局）" : "不完整：" + a.incompleteness()),
                a.isComplete() ? NamedTextColor.GREEN : NamedTextColor.RED));
        sender.sendMessage(Component.text("玩家传送点：" + fmt(a.playerSpawn()), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("观战席：" + fmt(a.spectateSpawn()), NamedTextColor.GRAY));
        for (int i = 1; i <= ArenaConfig.POINTS_PER_SIDE; i++) {
            sender.sendMessage(Component.text("  左 " + i + "：" + fmt(a.leftPoints().get(i))
                    + "   右 " + i + "：" + fmt(a.rightPoints().get(i)), NamedTextColor.DARK_GRAY));
        }
    }

    private static String fmt(Object o) {
        if (o == null) {
            return "未绑定";
        }
        if (o instanceof ArenaConfig.Point p) {
            return String.format(Locale.ROOT, "%.1f, %.1f, %.1f", p.x(), p.y(), p.z());
        }
        if (o instanceof ArenaConfig.Spawn s) {
            return String.format(Locale.ROOT, "%.1f, %.1f, %.1f", s.x(), s.y(), s.z());
        }
        return String.valueOf(o);
    }

    // ================= /ig debug =================

    private boolean debug(CommandSender sender, String[] args) {
        boolean debugAllowed = plugin.gameConfig().debugEnabled()
                && sender.hasPermission("infernalguess.play");
        if (!sender.hasPermission("infernalguess.admin") && !debugAllowed) {
            sender.sendMessage(plugin.displayConfig().render("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "用法：/ig debug <reload|preview|summon|skip|set|info>", NamedTextColor.YELLOW));
            return true;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                try {
                    plugin.reloadAllConfigs();
                    sender.sendMessage(plugin.displayConfig().render("reload-ok"));
                } catch (ConfigError e) {
                    plugin.getLogger().severe("热重载失败：" + e.getMessage());
                    sender.sendMessage(Component.text("重载失败：" + e.getMessage(), NamedTextColor.RED));
                }
            }
            case "preview" -> preview(sender, args, 2);
            case "mgc" -> mgcStatus(sender);
            case "info" -> {
                sender.sendMessage(Component.text("（对局状态在阶段 6~7 接入后可用）", NamedTextColor.GRAY));
            }
            case "summon" -> summon(sender, args);
            case "skip" -> notYet(sender, "跳过轮次", "阶段 6");
            case "set" -> setCmd(sender, args);
            case "bet" -> betCmd(sender, args);
            case "battle" -> battleCmd(sender, args);
            case "round" -> roundCmd(sender, args);
            // ★ 2026-10-12：`mode` 已**移出** debug，改挂到顶层 `/ig mode`（对所有人开放）。
            //   这里刻意不再保留别名 —— 用户要求的是"移出来"，不是"再开一个入口"。
            case "room" -> roomCmd(sender, args);
            case "mgcstart" -> mgcStartCmd(sender);
            case "world" -> worldCmd(sender);
            case "where" -> whereCmd(sender);
            default -> sender.sendMessage(Component.text(
                    "用法：/ig debug <reload|preview|summon|skip|set|info>", NamedTextColor.YELLOW));
        }
        return true;
    }

    // ================= 辅助 =================

    /**
     * {@code /ig debug mgc} —— 从 **MGC 自己的视角**核对注册结果。
     *
     * <p>为什么需要它：「房间 json 存在」不等于「MGC 认为可加入」。
     * 本指令直接问 MGC：游戏 id 注册了吗？meta 有吗？playerSelectable 为真吗？房间什么状态？
     */
    private boolean mgcStatus(CommandSender sender) {
        var gm = com.github.mczjuops.mczjugamecore.MCZJUGameCore.getGameManager();
        var rooms = com.github.mczjuops.mczjugamecore.MCZJUGameCore.getGameRoomManager()
                .getGameRooms(io.mczju.infernalguess.game.InfernalGuessGame.ID);

        sender.sendMessage(Component.text("─── MGC 注册状态 ───", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("已注册游戏 id：" + gm.getRegisteredGameIds(), NamedTextColor.GRAY));

        var meta = gm.getGameMetas().get(io.mczju.infernalguess.game.InfernalGuessGame.ID);
        if (meta == null) {
            sender.sendMessage(Component.text("本游戏 meta：❌ 未注册！", NamedTextColor.RED));
        } else {
            sender.sendMessage(Component.text("本游戏 meta：" + meta.displayName()
                    + " ｜ 图标 " + meta.icon() + " ｜ 作者 " + meta.author(), NamedTextColor.GRAY));
        }
        sender.sendMessage(Component.text("playerSelectable："
                + gm.playerSelectable(io.mczju.infernalguess.game.InfernalGuessGame.ID),
                NamedTextColor.GRAY));
        // 与已知可用的 Maggoteers 对比 —— 用于区分「我们的问题」还是「服务器大厅没配」
        for (String other : gm.getRegisteredGameIds()) {
            if (other.equals(io.mczju.infernalguess.game.InfernalGuessGame.ID)) {
                continue;
            }
            sender.sendMessage(Component.text("对比 " + other + "：playerSelectable="
                    + gm.playerSelectable(other), NamedTextColor.DARK_GRAY));
        }
        // 大厅系统（MGC 1.1.2 新增）——大厅未配置时游戏可能选不到
        try {
            var lobby = com.github.mczjuops.mczjugamecore.MCZJUGameCore.getLobbyManager();
            sender.sendMessage(Component.text("大厅已配置 id：" + lobby.getConfiguredLobbyIds(),
                    NamedTextColor.GRAY));
            boolean hasGameLobby = lobby.hasLobby(io.mczju.infernalguess.game.InfernalGuessGame.ID);
            sender.sendMessage(Component.text("本游戏是否有专属大厅（getGameLobby）：" + hasGameLobby,
                    hasGameLobby ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("主大厅（MAIN_LOBBY_ID="
                    + com.github.mczjuops.mczjugamecore.lobby.LobbyManager.MAIN_LOBBY_ID + "）："
                    + lobby.getMainLobby(), NamedTextColor.GRAY));
        } catch (Throwable t) {
            sender.sendMessage(Component.text("查询大厅信息失败：" + t, NamedTextColor.RED));
        }

        if (rooms == null || rooms.isEmpty()) {
            sender.sendMessage(Component.text("房间：❌ 一个都没有 → /mgc join 会提示「无空闲房间」",
                    NamedTextColor.RED));
        } else {
            sender.sendMessage(Component.text("房间数：" + rooms.size(), NamedTextColor.GRAY));
            for (var r : rooms) {
                sender.sendMessage(Component.text("  · " + r.getRoomName()
                        + " ｜ 状态 " + r.getState()
                        + " ｜ gameId " + r.getGameId(), NamedTextColor.DARK_GRAY));
            }
        }

        // ================= ★ 「没有空房间」的真正判据 =================
        //
        // 已用**字节码**核实 MGC 1.1.2 的两处实现，这是排查「无空闲房间」的关键：
        //   ① GameRoomManager.getRandomLeisureGameRoom(gameId) 的过滤条件只有一句：
        //        room.getState() == GameRoomState.READY
        //      过滤后为空 → 返回 null → /mgc join 就报「无空闲房间」。
        //   ② DefaultGameManager.createGame(...) 在**创建对局的瞬间**就把房间
        //        setState(IN_GAME)；只有 cancelGame / abortGame / endGame / forceAbortGame
        //        会经 solveGameEnd(...) 置回 READY。
        // 所以「房间存在但没一个 READY」= 有对局被创建了却没走到任何结束路径。
        // 另注：房间的 state **不持久化**（磁盘 json 里没有该字段），重启即恢复。
        long ready = rooms == null ? 0L
                : rooms.stream()
                       .filter(r -> r.getState()
                               == com.github.mczjuops.mczjugamecore.game.room.GameRoomState.READY)
                       .count();
        if (ready == 0) {
            sender.sendMessage(Component.text(
                    "READY 房间：❌ 0 个 → /mgc join 必然提示「无空闲房间」", NamedTextColor.RED));
        } else {
            sender.sendMessage(Component.text("READY 房间：" + ready + " 个 ✓（可加入）",
                    NamedTextColor.GREEN));
        }

        // ★ 再看有没有「盘踞中的对局」—— 它们才是房间被占住的原因
        int mine = 0;
        try {
            for (var g : gm.getAllGames()) {
                if (!io.mczju.infernalguess.game.InfernalGuessGame.ID.equals(g.getId())) {
                    continue;
                }
                mine++;
                String roomName = g.getGameRoom() == null
                        ? "（未绑定房间）" : g.getGameRoom().getRoomName();
                sender.sendMessage(Component.text("  ⚠ 未释放的对局：房间 " + roomName
                        + " ｜ 状态 " + g.getState(), NamedTextColor.YELLOW));
            }
        } catch (Throwable t) {
            sender.sendMessage(Component.text("查询对局列表失败：" + t, NamedTextColor.RED));
        }
        if (mine == 0) {
            sender.sendMessage(Component.text("MGC 中本游戏的对局：0 个（没有占着房间的对局）",
                    NamedTextColor.GRAY));
        } else if (ready == 0) {
            sender.sendMessage(Component.text(
                    "  → 上面这些对局占着房间却没释放（正常应已 end/abort）。"
                            + "执行 /ig debug room reset 可**不重启**强制复位", NamedTextColor.YELLOW));
        }
        return true;
    }

    /**
     * {@code /ig debug where} —— 打印各世界的 spawn 坐标。
     *
     * <p>用途：无客户端时（控制台）也能知道该把竞技场点位绑到哪里。
     */
    private boolean whereCmd(CommandSender sender) {
        sender.sendMessage(Component.text("─── 世界 spawn ───", NamedTextColor.GOLD));
        for (org.bukkit.World w : sender.getServer().getWorlds()) {
            var s = w.getSpawnLocation();
            sender.sendMessage(Component.text(String.format(Locale.ROOT,
                    "  %-16s spawn = %.1f, %.1f, %.1f ｜ 环境 %s ｜ 实体 %d",
                    w.getName(), s.getX(), s.getY(), s.getZ(), w.getEnvironment(), w.getEntities().size()),
                    NamedTextColor.GRAY));
        }
        if (sender instanceof Player p) {
            var l = p.getLocation();
            sender.sendMessage(Component.text(String.format(Locale.ROOT,
                    "  你当前：%s %.1f, %.1f, %.1f",
                    l.getWorld().getName(), l.getX(), l.getY(), l.getZ()), NamedTextColor.AQUA));
        }
        return true;
    }

    /**
     * {@code /ig debug summon <left|right> <组id> [词条1,词条2]} —— **定点注入**一只怪。
     *
     * <p>解决的是验证效率：不再靠改配置 + 换种子等剧本抽中目标怪，
     * 而是**一条命令直接造出「指定怪 + 指定词条」**。
     * 走的是与正常开局**完全一致**的装配链（见 {@code BattleService.summonGroup}），
     * 所以验证结论能代表真实开局。
     *
     * <p>典型用法：
     * <pre>
     *   /ig debug battle start test1 5
     *   /ig debug summon right llama 1up        ← 验 1up 的 WARD 拦截
     *   /ig debug summon right skeleton archer  ← 验 archer 的箭与阵营过滤
     *   /ig debug summon left zombie sprint     ← 验 sprint（STAT）
     *   /ig debug battle status                 ← 看装备与药水
     *   /ig debug battle fight
     * </pre>
     */
    private boolean summon(CommandSender sender, String[] args) {
        if (args.length < 4) {
            usage(sender, "/ig debug summon <left|right> <组id> [词条1,词条2]");
            return true;
        }
        var battle = io.mczju.infernalguess.battle.BattleService.current().orElse(null);
        if (battle == null) {
            sender.sendMessage(Component.text(
                    "当前没有战斗。先 /ig debug battle start <竞技场> [回合]。", NamedTextColor.RED));
            return true;
        }
        var side = args[2].equalsIgnoreCase("left")
                ? io.mczju.infernalguess.battle.BattleSide.LEFT
                : io.mczju.infernalguess.battle.BattleSide.RIGHT;
        var group = plugin.mobGroupConfig().groups().get(args[3].toLowerCase(Locale.ROOT));
        if (group == null) {
            sender.sendMessage(Component.text("没有这个怪组：" + args[3]
                    + "（用 /ig debug info 可看已加载的组数）", NamedTextColor.RED));
            return true;
        }
        java.util.List<String> affixes = new java.util.ArrayList<>();
        if (args.length >= 5 && !args[4].isBlank()) {
            for (String s : args[4].split(",")) {
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (t.isEmpty()) {
                    continue;
                }
                // 写错 id 就直接报出来 —— 否则会以为「词条没生效」而白查半天
                if (!plugin.affixRegistry().isKnown(t)) {
                    sender.sendMessage(Component.text("词条 id 未实现：" + t
                            + "（已实现 " + plugin.affixRegistry().size() + " 个）", NamedTextColor.RED));
                    return true;
                }
                affixes.add(t);
            }
        }
        var arena = battle.arena();
        var world = org.bukkit.Bukkit.getWorld(arena.world());
        if (world == null) {
            sender.sendMessage(Component.text("竞技场世界未加载：" + arena.world(), NamedTextColor.RED));
            return true;
        }
        var points = side == io.mczju.infernalguess.battle.BattleSide.LEFT
                ? arena.leftPoints() : arena.rightPoints();
        var point = points.get(1);
        if (point == null) {
            sender.sendMessage(Component.text("该侧缺少 1 号刷怪点。", NamedTextColor.RED));
            return true;
        }
        var mob = battle.summonGroup(group, affixes, side, point.toLocation(world));
        // 第 6 个参数 force：忽略 chance 概率闸门（调试专用，见 AffixRegistry#forceChance）。
        // 带概率的词条（如 mama+ 的 chance 0.15）靠跑一局根本验不可靠 ——
        // 而「可测性」该由测试入口的开关解决，不能去改被测数值。
        boolean force = args.length >= 6 && args[5].equalsIgnoreCase("force");
        if (force) {
            plugin.affixRegistry().setForceChance(true);
        }
        if (mob == null) {
            sender.sendMessage(Component.text("注入失败（该 EntityType 无法生成？）",
                    NamedTextColor.RED));
        } else {
            sender.sendMessage(Component.text("已注入 " + group.id() + "（" + side.id() + "）"
                    + (affixes.isEmpty() ? " 无词条" : " 词条=" + affixes)
                    + (force ? " [force：忽略 chance]" : "")
                    + " → " + mob.getUniqueId().toString().substring(0, 8), NamedTextColor.GREEN));
        }
        return true;
    }

    /**
     * {@code /ig debug battle ...} —— **无客户端实测战斗**的入口。
     *
     * <pre>
     *   /ig debug battle start &lt;竞技场&gt; [回合] [seed]   生成双方并锁 AI
     *   /ig debug battle fight                          归还 AI，开打
     *   /ig debug battle status                         打印双方存活
     *   /ig debug battle stop                           清场
     * </pre>
     */
    private boolean battleCmd(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender, "/ig debug battle <start <竞技场> [回合] [seed] | fight | status | stop>");
            return true;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        switch (action) {
            case "start" -> {
                if (args.length < 4) {
                    usage(sender, "/ig debug battle start <竞技场> [回合] [seed]");
                    return true;
                }
                var arena = plugin.arenaConfig().get(args[3]).orElse(null);
                if (arena == null) {
                    sender.sendMessage(Component.text("竞技场不存在：" + args[3], NamedTextColor.RED));
                    return true;
                }
                if (!arena.isComplete()) {
                    sender.sendMessage(Component.text("竞技场不完整（缺 " + arena.incompleteness()
                            + "），无法开战。", NamedTextColor.RED));
                    return true;
                }
                var world = ArenaConfig.resolveWorld(arena).orElse(null);
                if (world == null) {
                    sender.sendMessage(Component.text("竞技场世界未加载：" + arena.world(),
                            NamedTextColor.RED));
                    return true;
                }
                int round = 1;
                long seed = System.nanoTime();
                if (args.length >= 5) {
                    try {
                        round = Integer.parseInt(args[4]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Component.text("回合数必须是数字", NamedTextColor.RED));
                        return true;
                    }
                }
                if (args.length >= 6) {
                    try {
                        seed = Long.parseLong(args[5]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Component.text("seed 必须是数字", NamedTextColor.RED));
                        return true;
                    }
                }
                GameConfig g = plugin.gameConfig();
                if (round < 1 || round > g.rounds()) {
                    sender.sendMessage(Component.text("回合应在 1~" + g.rounds(), NamedTextColor.RED));
                    return true;
                }
                RoundPlan plan = RunPlanner.planRound(new SeededRng(seed), round, g,
                        plugin.mobGroupConfig(), plugin.mobGroupConfig().ids());
                try {
                    var battle = new io.mczju.infernalguess.battle.BattleService(plugin);
                    battle.prepare(plan, arena, world);
                    sender.sendMessage(Component.text("═══ 已生成第 " + round + " 回合（seed "
                            + seed + "）═══", NamedTextColor.GOLD));
                    sender.sendMessage(Component.text("左：" + plan.describeSide(plan.left())
                            + "  → 权重 " + round(plan.left().exactWeight()), NamedTextColor.AQUA));
                    sender.sendMessage(Component.text("右：" + plan.describeSide(plan.right())
                            + "  → 权重 " + round(plan.right().exactWeight()), NamedTextColor.RED));
                    sender.sendMessage(Component.text("AI 已锁定。用 /ig debug battle fight 开打，"
                            + "/ig debug battle stop 清场。", NamedTextColor.YELLOW));
                } catch (IllegalStateException e) {
                    sender.sendMessage(Component.text("开战失败：" + e.getMessage(), NamedTextColor.RED));
                }
            }
            case "fight" -> io.mczju.infernalguess.battle.BattleService.current()
                    .ifPresentOrElse(b -> {
                        b.beginFight();
                        sender.sendMessage(Component.text("▶ 已归还 AI 并挂上索敌 Goal。",
                                NamedTextColor.GREEN));
                    }, () -> sender.sendMessage(Component.text("当前没有战斗。", NamedTextColor.RED)));
            case "status" -> io.mczju.infernalguess.battle.BattleService.current()
                    .ifPresentOrElse(b -> {
                        var t = b.tracker();
                        sender.sendMessage(Component.text("第 " + b.round() + " 回合"
                                + " ｜ 开打=" + b.isFighting() + " ｜ 胜方=" + b.winner().orElse(null)
                                + " ｜ 追踪 " + t.size() + " 个", NamedTextColor.GOLD));
                        for (var side : io.mczju.infernalguess.battle.BattleSide.values()) {
                            sender.sendMessage(Component.text("  " + side.id()
                                    + "：计数存活 " + t.countedAlive(side)
                                    + " ｜ 全部存活 " + t.totalAlive(side), NamedTextColor.GRAY));
                        }
                        // 逐个列出：定位「生成后立刻消失」这类问题；同时显示阵营名牌
                        for (var u : t.all()) {
                            var e = org.bukkit.Bukkit.getEntity(u.uuid());
                            String desc;
                            if (e == null) {
                                desc = "❌ Bukkit.getEntity 返回 null（不在已加载区块内？）";
                            } else {
                                var l = e.getLocation();
                                String hp = e instanceof org.bukkit.entity.LivingEntity le
                                        ? String.format(Locale.ROOT, " hp=%.1f", le.getHealth()) : "";
                                String name = "";
                                StringBuilder extra = new StringBuilder();
                                if (e instanceof org.bukkit.entity.LivingEntity le) {
                                    if (le.customName() != null) {
                                        name = " 名牌「" + net.kyori.adventure.text.serializer.plain
                                                .PlainTextComponentSerializer.plainText()
                                                .serialize(le.customName()) + "」";
                                    }
                                    // ★ 打印装备与药水效果。
                                    //   为什么必须：sprint / bullwark / armoured / armoured+ 这类
                                    //   STAT 词条是在**装配时**一次性生效、之后**不触发任何日志**的
                                    //   → 只看触发日志永远无法验证它们。这里把「结果」打出来，
                                    //   STAT 词条就变成可验证的了（实测需求，见 dev-log 035 §7）。
                                    java.util.function.Function<org.bukkit.inventory.ItemStack, String> sn =
                                            it -> (it == null || it.getType().isAir())
                                                    ? "-" : it.getType().getKey().getKey();
                                    // ★ 附魔也要打出来 —— 否则「骷髅马骑士的耐久 III 铁头盔」
                                    //   这类配置只能看到材质、看不到附魔，等于**没法验证**。
                                    //   （骑乘组合里 26.2 的耐久 key 是 unbreaking，不是 durability。）
                                    java.util.function.Function<org.bukkit.inventory.ItemStack, String> ench =
                                            it -> {
                                                if (it == null || it.getType().isAir()
                                                        || it.getItemMeta() == null
                                                        || it.getItemMeta().getEnchants().isEmpty()) {
                                                    return "-";
                                                }
                                                StringBuilder eb = new StringBuilder();
                                                it.getItemMeta().getEnchants()
                                                        .forEach((k, v) -> {
                                                            if (eb.length() > 0) {
                                                                eb.append(',');
                                                            }
                                                            eb.append(k.getKey().getKey())
                                                              .append(':').append(v);
                                                        });
                                                return eb.toString();
                                            };
                                    var eq = le.getEquipment();
                                    if (eq != null) {
                                        extra.append(" 甲[")
                                             .append(sn.apply(eq.getHelmet())).append('/')
                                             .append(sn.apply(eq.getChestplate())).append('/')
                                             .append(sn.apply(eq.getLeggings())).append('/')
                                             .append(sn.apply(eq.getBoots())).append(']')
                                             .append(" 手[").append(sn.apply(eq.getItemInMainHand()))
                                             .append(']')
                                             .append(" 附魔[")
                                             .append(ench.apply(eq.getHelmet())).append('|')
                                             .append(ench.apply(eq.getItemInMainHand())).append(']');
                                    }
                                    if (!le.getActivePotionEffects().isEmpty()) {
                                        extra.append(" 效果[");
                                        boolean first = true;
                                        for (var pe : le.getActivePotionEffects()) {
                                            if (!first) {
                                                extra.append(',');
                                            }
                                            first = false;
                                            extra.append(pe.getType().getKey().getKey())
                                                 .append(':').append(pe.getAmplifier() + 1);
                                        }
                                        extra.append(']');
                                    }
                                }
                                desc = String.format(Locale.ROOT, "%.1f,%.1f,%.1f%s dead=%s valid=%s",
                                        l.getX(), l.getY(), l.getZ(), hp, e.isDead(), e.isValid())
                                        + name + extra;
                            }
                            sender.sendMessage(Component.text("    · " + u.side().id()
                                    + (u.counted() ? " 计数" : " 非计数")
                                    + " " + u.uuid().toString().substring(0, 8) + " → " + desc,
                                    NamedTextColor.DARK_GRAY));
                        }
                    }, () -> sender.sendMessage(Component.text("当前没有战斗。", NamedTextColor.RED)));
            case "stop" -> io.mczju.infernalguess.battle.BattleService.current()
                    .ifPresentOrElse(b -> {
                        b.cleanup();
                        sender.sendMessage(Component.text("已清场。", NamedTextColor.GREEN));
                    }, () -> sender.sendMessage(Component.text("当前没有战斗。", NamedTextColor.RED)));
            default -> usage(sender, "/ig debug battle <start|fight|status|stop>");
        }
        return true;
    }

    /**
     * {@code /ig debug round ...} —— **无客户端跑完整局**（回合状态机 + 下注结算 + 人机决策）。
     *
     * <pre>
     *   /ig debug round start &lt;竞技场&gt; [人机数] [下注秒数] [回合上限] [seed]
     *   /ig debug round status
     *   /ig debug round stop
     * </pre>
     */
    private boolean roundCmd(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender, "/ig debug round <start <竞技场> [人机数] [下注秒] [回合上限] [seed] | status | stop>");
            return true;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        switch (action) {
            case "start" -> {
                if (args.length < 4) {
                    usage(sender, "/ig debug round start <竞技场> [人机数] [下注秒] [回合上限] [seed]");
                    return true;
                }
                if (plugin.activeRound().filter(r -> !r.isEnded()).isPresent()) {
                    sender.sendMessage(Component.text("已有对局在进行，先 /ig debug round stop。",
                            NamedTextColor.RED));
                    return true;
                }
                var arena = plugin.arenaConfig().get(args[3]).orElse(null);
                if (arena == null || !arena.isComplete()) {
                    sender.sendMessage(Component.text("竞技场不存在或不完整：" + args[3],
                            NamedTextColor.RED));
                    return true;
                }
                var world = ArenaConfig.resolveWorld(arena).orElse(null);
                if (world == null) {
                    sender.sendMessage(Component.text("竞技场世界未加载：" + arena.world(),
                            NamedTextColor.RED));
                    return true;
                }
                int bots = args.length >= 5 ? parseInt(args[4], 4) : 4;
                int betSec = args.length >= 6 ? parseInt(args[5], 2) : 2;
                int limit = args.length >= 7 ? parseInt(args[6], 0) : 0;
                long seed = args.length >= 8 ? parseLong(args[7], 20261011L) : 20261011L;

                GameConfig cfg = plugin.gameConfig();
                // 模式可覆盖（用于在不改 config.yml 的情况下实测终极模式）
                io.mczju.infernalguess.config.GameMode mode = cfg.mode();
                if (args.length >= 9) {
                    try {
                        mode = io.mczju.infernalguess.config.GameMode
                                .valueOf(args[8].toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage(Component.text(
                                "未知模式：" + args[8] + "（可选 BASIC | ULTIMATE）", NamedTextColor.RED));
                        return true;
                    }
                }

                List<io.mczju.infernalguess.game.GamePlayer> players = new ArrayList<>();
                List<String> botNames = cfg.botNames();
                for (int i = 0; i < bots; i++) {
                    String name = i < botNames.size() ? botNames.get(i) : ("[AI]" + (i + 1));
                    players.add(new io.mczju.infernalguess.game.GamePlayer(
                            java.util.UUID.nameUUIDFromBytes((name + seed).getBytes()),
                            name, true, mode,
                            cfg.initialFunds(), cfg.ultimateLife(), cfg.ultimateShield()));
                }
                var manager = new io.mczju.infernalguess.game.RoundManager(
                        plugin, players, arena, world, seed, betSec, limit, mode);
                plugin.setActiveRound(manager);
                sender.sendMessage(Component.text("═══ 对局开始 ═══", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("seed " + seed + " ｜ 人机 " + bots
                        + " ｜ 下注 " + betSec + " 秒 ｜ 回合上限 "
                        + (limit > 0 ? limit : cfg.rounds()) + " ｜ 模式 " + mode,
                        NamedTextColor.GRAY));
                manager.start();
            }
            case "status" -> {
                var opt = plugin.activeRound();
                if (opt.isEmpty()) {
                    sender.sendMessage(Component.text("当前没有对局。", NamedTextColor.RED));
                    return true;
                }
                var active = opt.get();
                var m = active.statusMap();
                sender.sendMessage(Component.text("第 " + m.get("round")
                        + " ｜ 阶段 " + m.get("phase")
                        + " ｜ 倒计时 " + m.get("countdown")
                        + " ｜ 上回合胜方 " + m.get("lastWinner")
                        + " ｜ 模式 " + active.mode(), NamedTextColor.GOLD));
                boolean ultimate = !active.mode().usesFunds();
                for (var p : active.players()) {
                    // 终极模式不显示资金与百分比（用户反馈：这个模式没有资金概念）
                    String bet = p.bet().watch() ? "观望"
                            : (ultimate ? (p.bet().side() == io.mczju.infernalguess.battle.BattleSide.LEFT
                                    ? "左" : "右")
                            : p.bet().side().id() + " " + p.bet().percentLabel());
                    String stats = ultimate
                            ? ("生命 " + p.life() + " 护盾 " + p.shield())
                            : ("资金 " + p.funds());
                    sender.sendMessage(Component.text("  " + p.name()
                            + " ｜ 押 " + bet
                            + " ｜ " + stats
                            + (p.isAlive() ? "" : " 【出局】"), NamedTextColor.GRAY));
                }
            }
            case "stop" -> {
                var opt = plugin.activeRound();
                if (opt.isEmpty()) {
                    sender.sendMessage(Component.text("当前没有对局。", NamedTextColor.RED));
                    return true;
                }
                opt.get().stop("调试指令");
                plugin.setActiveRound(null);
                plugin.scoreboardService().clearAll();
                sender.sendMessage(Component.text("已中止对局。", NamedTextColor.GREEN));
            }
            default -> usage(sender, "/ig debug round <start|status|stop>");
        }
        return true;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * {@code /ig debug room [竞技场名]} —— 查看/设置房间绑定的竞技场。
     *
     * <p>等价于 {@code /mgcop room edit infernalguess default}，但可以在控制台用，
     * 且只改我们自己的字段。
     */
    private boolean roomCmd(CommandSender sender, String[] args) {
        // ★ /ig debug room reset —— 复位卡住的房间（**不用重启服务器**）
        if (args.length >= 3 && "reset".equalsIgnoreCase(args[2])) {
            return roomReset(sender);
        }
        var room = plugin.firstRoom();
        if (room == null) {
            sender.sendMessage(Component.text("找不到本游戏的房间实例（/ig debug mgc 查看）。",
                    NamedTextColor.RED));
            return true;
        }
        if (args.length >= 3) {
            String name = args[2];
            if (plugin.arenaConfig().get(name).isEmpty()) {
                sender.sendMessage(Component.text("竞技场不存在：" + name, NamedTextColor.RED));
                return true;
            }
            room.arenaName = name;
            room.setModified(true); // ⚠️ 不标记就不会落盘
            boolean saved = room.save();
            sender.sendMessage(Component.text("房间 " + room.getRoomName()
                    + " 已绑定竞技场 " + name + "（落盘 " + saved + "）",
                    saved ? NamedTextColor.GREEN : NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(Component.text("房间 " + room.getRoomName()
                + " ｜ 状态 " + room.getState()
                + " ｜ 绑定竞技场 " + (room.arenaName == null ? "（未绑定，开局时自动挑选）"
                        : room.arenaName), NamedTextColor.GOLD));
        return true;
    }

    /**
     * {@code /ig debug mgcstart} —— **不需要玩家**就创建并启动一局真正的 MGC 对局。
     *
     * <h2>为什么需要它（为了能复现/验证）</h2>
     * 有两个缺陷**只能在「有真实 MGC 对局」时验证**：
     * <ol>
     *   <li>★ 关服时 MGC 停止本游戏报
     *       {@code IllegalPluginAccessException: Plugin attempted to register task
     *       while disabled} —— 用户日志里只有消息、**没有堆栈**，无法定位是谁在调度；
     *       必须能复现才能拿到堆栈</li>
     *   <li>结束对局是否真的释放了房间（`GameManager.endGame` 那条修复）</li>
     * </ol>
     * 而这两件事正常都需要玩家 {@code /mgc join} —— 我没有客户端，做不到。
     * 本命令直接从 MGC 的 API 创建并开局（玩家列表为空，参与者靠人机补足），
     * 于是「创建 → 开局 → 关服」这条链可以在**纯控制台**下走通。
     *
     * <p>用法：{@code /ig debug mgcstart} → 确认开局 → 直接 {@code stop} 关服 →
     * 看日志里有没有那条异常、以及它的完整堆栈。
     */
    private boolean mgcStartCmd(CommandSender sender) {
        var gm = com.github.mczjuops.mczjugamecore.MCZJUGameCore.getGameManager();
        String id = io.mczju.infernalguess.game.InfernalGuessGame.ID;
        try {
            var game = gm.createGame(id);
            if (game == null) {
                sender.sendMessage(Component.text("createGame 返回 null → 没有空闲房间"
                        + "（用 /ig debug mgc 看 READY 数量；必要时 /ig debug room reset）",
                        NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text("已创建对局，房间 "
                    + (game.getGameRoom() == null ? "?" : game.getGameRoom().getRoomName())
                    + " ｜ 状态 " + game.getState(), NamedTextColor.GREEN));
            gm.startGame(game);
            sender.sendMessage(Component.text("已请求 startGame；随后请直接 stop 关服，"
                    + "以复现「关服时停止对局」那条链路。", NamedTextColor.YELLOW));
        } catch (Throwable t) {
            sender.sendMessage(Component.text("创建/启动对局失败：" + t, NamedTextColor.RED));
            plugin.getLogger().warning("[IG] mgcstart 失败：" + t);
        }
        return true;
    }

    /**
     * {@code /ig debug world} —— 对**本局竞技场所在世界**应用 {@code world.*} 配置，
     * 并**逐条回读实际值**打印出来。
     *
     * <h2>为什么需要它（真实缺陷 + 可验证性）</h2>
     * 实测发现配置里的 5 条游戏规则**全部没生效**：日志刷
     * 「未知游戏规则 doMobSpawning / mobGriefing / naturalRegeneration /
     * keepInventory / doMobLoot，已跳过」。
     * 根因是旧实现用了 {@code world.setGameRuleValue(String,String)} ——
     * 26.2 上它按**枚举常量名**查找，而配置写的是**原版命令名**（camelCase）。
     *
     * <p>而 {@code WorldSetup.apply} 原先只在 MGC **真实开局**时才被调用 ——
     * 也就是说：不开一局就验证不了。本命令把这件事变成**随时可验**：
     * 直接应用 + **回读实际值**与配置对比，一条命令就能确认修好没有。
     */
    private boolean worldCmd(CommandSender sender) {
        var cfg = plugin.gameConfig();
        var arena = plugin.arenaConfig().playable().stream().findFirst().orElse(null);
        if (arena == null) {
            sender.sendMessage(Component.text("没有可用的竞技场（/ig debug arena list）。",
                    NamedTextColor.RED));
            return true;
        }
        var world = io.mczju.infernalguess.config.ArenaConfig.resolveWorld(arena).orElse(null);
        if (world == null) {
            sender.sendMessage(Component.text("竞技场世界未加载：" + arena.world(),
                    NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(Component.text("─── 应用 world.* 到 " + world.getName()
                + "（竞技场 " + arena.name() + "）───", NamedTextColor.GOLD));

        io.mczju.infernalguess.game.WorldSetup.apply(world,
                cfg.worldDifficulty(), cfg.timeLock(), cfg.gamerules(),
                w -> sender.sendMessage(Component.text("  ⚠ " + w, NamedTextColor.YELLOW)));

        // ★ 逐条**回读实际值** —— 这才是「到底生效没有」的证据
        if (cfg.gamerules().isEmpty()) {
            sender.sendMessage(Component.text("  （配置里没有 world.gamerules）",
                    NamedTextColor.GRAY));
        } else {
            java.util.List<String> unresolved = new java.util.ArrayList<>();
            for (var e : cfg.gamerules().entrySet()) {
                var rule = io.mczju.infernalguess.game.WorldSetup.resolveRule(e.getKey());
                if (rule == null) {
                    unresolved.add(e.getKey());
                    sender.sendMessage(Component.text("  ✗ " + e.getKey()
                            + "：规则名无法解析", NamedTextColor.RED));
                    continue;
                }
                // ⚠️ 类型化读取的 API 名是 getGameRuleValue(GameRule<T>)，
                //    不是 getGameRule（后者在 26.2 里不存在，编译器抓过我这个错）
                Object actual = world.getGameRuleValue(
                        (org.bukkit.GameRule<Object>) (org.bukkit.GameRule<?>) rule);
                boolean ok = e.getValue().equals(actual);
                sender.sendMessage(Component.text("  " + (ok ? "✓ " : "✗ ")
                        + e.getKey() + "：配置 " + e.getValue() + " ｜ 实际 " + actual,
                        ok ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            // ★ 解析失败时**把服务器注册表里的真实键名打出来** ——
            //   这是「这台服务器上到底叫什么」的唯一权威答案，
            //   比照着版本印象猜命名规则可靠得多（实测：keepInventory/mobGriefing 能解析，
            //   doMobSpawning/naturalRegeneration/doMobLoot 不能，命名并不统一）。
            if (!unresolved.isEmpty()) {
                java.util.List<String> keys = new java.util.ArrayList<>();
                for (var r : org.bukkit.Registry.GAME_RULE) {
                    keys.add(r.getKey().toString());
                }
                java.util.Collections.sort(keys);
                sender.sendMessage(Component.text("  本服务器注册表里的全部游戏规则键（共 "
                        + keys.size() + " 个）：", NamedTextColor.GRAY));
                sender.sendMessage(Component.text("    " + String.join(", ", keys),
                        NamedTextColor.DARK_GRAY));
            }
        }
        sender.sendMessage(Component.text("难度 " + world.getDifficulty()
                + " ｜ 时间 " + world.getTime(), NamedTextColor.GRAY));
        return true;
    }

    /**
     * {@code /ig debug room reset} —— **复位卡住的房间，不用重启服务器**。
     *
     * <h2>为什么需要它（排查「无空闲房间」时查到的机制）</h2>
     * 用字节码核实过 MGC 1.1.2 的两件事：
     * <ol>
     *   <li>{@code GameRoomManager.getRandomLeisureGameRoom()} 判定「空闲」的条件只有
     *       {@code room.getState() == READY}；过滤后为空就返回 {@code null}
     *       → {@code /mgc join} 报「无空闲房间」</li>
     *   <li>{@code DefaultGameManager.createGame()} 在**创建对局的瞬间**就把房间置为
     *       {@code IN_GAME}；只有 {@code cancelGame/abortGame/endGame/forceAbortGame}
     *       会经 {@code solveGameEnd()} 置回 {@code READY}</li>
     * </ol>
     * 所以只要有对局「创建了却没走到任何结束路径」，房间就被**永久占住**
     * （房间 state 不落盘，所以重启能恢复 —— 但重启代价太大）。
     *
     * <h2>两步复位</h2>
     * <ol>
     *   <li>先对盘踞的对局调 MGC **正规的** {@code forceAbortGame} ——
     *       它会走 {@code solveGameEnd} 释放房间，比我们直接改 state 更正确
     *       （顺带停掉对局对象本身）</li>
     *   <li>仍非 READY 的房间再兜底直接置 READY 并落盘</li>
     * </ol>
     */
    private boolean roomReset(CommandSender sender) {
        var gm = com.github.mczjuops.mczjugamecore.MCZJUGameCore.getGameManager();
        var roomMgr = com.github.mczjuops.mczjugamecore.MCZJUGameCore.getGameRoomManager();
        String gameId = io.mczju.infernalguess.game.InfernalGuessGame.ID;

        // ① 用 MGC 的正规强制停止入口处理卡住的对局
        int aborted = 0;
        try {
            for (var g : new java.util.ArrayList<>(gm.getAllGames())) {
                if (!gameId.equals(g.getId())) {
                    continue;
                }
                String roomName = g.getGameRoom() == null
                        ? "（未绑定）" : g.getGameRoom().getRoomName();
                try {
                    gm.forceAbortGame(g);
                    aborted++;
                    plugin.getLogger().info("[IG] 已强制停止卡住的对局：房间 "
                            + roomName + "（原状态 " + g.getState() + "）");
                } catch (Throwable t) {
                    plugin.getLogger().warning("[IG] 强制停止对局失败（房间 " + roomName
                            + "）：" + t);
                }
            }
        } catch (Throwable t) {
            sender.sendMessage(Component.text("枚举对局失败：" + t, NamedTextColor.RED));
        }

        // ② 兜底：仍有非 READY 的房间 → 直接置 READY
        int fixed = 0;
        var rooms = roomMgr.getGameRooms(gameId);
        if (rooms != null) {
            for (var r : rooms) {
                if (r.getState()
                        != com.github.mczjuops.mczjugamecore.game.room.GameRoomState.READY) {
                    var before = r.getState();
                    r.setState(
                            com.github.mczjuops.mczjugamecore.game.room.GameRoomState.READY);
                    r.setModified(true); // ⚠️ 不标记不会落盘
                    fixed++;
                    plugin.getLogger().info("[IG] 房间 " + r.getRoomName()
                            + " 状态 " + before + " → READY");
                }
            }
            if (fixed > 0) {
                roomMgr.saveGameRoom(gameId);
            }
        }

        long ready = rooms == null ? 0L
                : rooms.stream().filter(r -> r.getState()
                        == com.github.mczjuops.mczjugamecore.game.room.GameRoomState.READY)
                       .count();
        sender.sendMessage(Component.text("复位完成：强制停止对局 " + aborted
                + " 个 ｜ 直接改状态 " + fixed + " 个 ｜ 现在 READY 房间 " + ready + " 个",
                ready > 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (ready == 0) {
            sender.sendMessage(Component.text(
                    "仍有 0 个 READY 房间 —— 请把上面的输出发我，多半是房间列表本身为空"
                            + "（用 /mgcop room create " + gameId + " default 新建）。",
                    NamedTextColor.RED));
        }
        return true;
    }

    /**
     * {@code /ig debug bet <序号> <left|right|watch> [比例]} —— 以某个参与者的身份下注。
     *
     * <p>用途：控制台没有玩家，这是**实测真人下注路径（含斩杀线约束）**的唯一办法。
     *
     * @return 是否被接受（被拒绝时打印原因）
     */
    private boolean betCmd(CommandSender sender, String[] args) {
        var opt = plugin.activeRound();
        if (opt.isEmpty()) {
            sender.sendMessage(Component.text("当前没有对局。", NamedTextColor.RED));
            return true;
        }
        var active = opt.get();
        if (args.length < 4) {
            usage(sender, "/ig debug bet <序号> <left|right|watch> [比例]");
            return true;
        }
        int idx = parseInt(args[2], -1);
        if (idx < 0 || idx >= active.players().size()) {
            sender.sendMessage(Component.text("序号应在 0~" + (active.players().size() - 1),
                    NamedTextColor.RED));
            return true;
        }
        var gp = active.players().get(idx);
        String kind = args[3].toLowerCase(Locale.ROOT);
        io.mczju.infernalguess.betting.Bet bet;
        if (kind.startsWith("w")) {
            bet = io.mczju.infernalguess.betting.Bet.watching();
        } else {
            var side = kind.startsWith("r")
                    ? io.mczju.infernalguess.battle.BattleSide.RIGHT
                    : io.mczju.infernalguess.battle.BattleSide.LEFT;
            double ratio = 0.25;
            if (args.length >= 5) {
                try {
                    ratio = Double.parseDouble(args[4]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("比例必须是数字", NamedTextColor.RED));
                    return true;
                }
            }
            try {
                bet = io.mczju.infernalguess.betting.Bet.of(side, ratio);
            } catch (IllegalArgumentException e) {
                sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
                return true;
            }
        }
        boolean accepted = active.placeBet(gp.uuid(), bet);
        long killLine = plugin.gameConfig().killLineForRound(active.round());
        if (accepted) {
            sender.sendMessage(Component.text("✔ " + gp.name() + " 已下注 "
                    + (bet.watch() ? "观望" : bet.side().id() + " " + bet.percentLabel()),
                    NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("✘ 下注被拒绝：" + gp.name()
                    + " 资金 " + gp.funds() + "，本回合斩杀线 " + killLine
                    + "（阶段 " + active.phase() + "）", NamedTextColor.RED));
        }
        return true;
    }

    /** {@code /ig debug set funds|shield|life <序号> <值>} —— 直接改参与者状态。 */
    private boolean setCmd(CommandSender sender, String[] args) {
        var opt = plugin.activeRound();
        if (opt.isEmpty()) {
            sender.sendMessage(Component.text("当前没有对局。", NamedTextColor.RED));
            return true;
        }
        if (args.length < 5) {
            usage(sender, "/ig debug set <funds|shield|life> <序号> <值>");
            return true;
        }
        var active = opt.get();
        int idx = parseInt(args[3], -1);
        if (idx < 0 || idx >= active.players().size()) {
            sender.sendMessage(Component.text("序号应在 0~" + (active.players().size() - 1),
                    NamedTextColor.RED));
            return true;
        }
        long value = parseLong(args[4], Long.MIN_VALUE);
        if (value == Long.MIN_VALUE) {
            sender.sendMessage(Component.text("值必须是数字", NamedTextColor.RED));
            return true;
        }
        var gp = active.players().get(idx);
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "funds" -> gp.setFunds(value);
            case "shield" -> gp.setShield((int) value);
            case "life" -> gp.setLife((int) value);
            default -> {
                usage(sender, "/ig debug set <funds|shield|life> <序号> <值>");
                return true;
            }
        }
        sender.sendMessage(Component.text(gp.name() + " → 资金 " + gp.funds()
                + " ｜ 生命 " + gp.life() + " 护盾 " + gp.shield(), NamedTextColor.GREEN));
        return true;
    }

    /**
     * {@code /ig debug mode ...} —— **不改 config.yml 就能切本局设置**，方便快速测试。
     *
     * <pre>
     *   /ig debug mode                         查看当前生效设置
     *   /ig debug mode funds|ultimate|config   资金模式 / 终极模式 / 恢复配置值
     *   /ig debug mode bots &lt;数量|off|config&gt;  人机数量（off = 关，config = 恢复配置值）
     *   /ig debug mode infernal on|off|config  炒鸡开关
     *   /ig debug mode reset                   清空全部覆盖
     * </pre>
     *
     * <p>覆盖只影响**之后新开/新生成**的内容（模式在开局时定格），不会改动 config.yml。
     */
    /**
     * 让 {@code /ig mode ...} **复用** {@code /ig debug mode ...} 的实现。
     *
     * <p>做法是把参数整体右移一位并补一个假前缀（{@code [ig, debug, 原 args[1..]]}），
     * 于是 {@link #modeCmd} 里的 {@code args[2]} / {@code args[3]} 原封不动 ——
     * <b>子树结构、分支逻辑、实现一行都不改</b>，只换了入口。
     *
     * <p>为什么不改 {@code modeCmd} 的下标：那要动它内部每一处 {@code args[n]}，
     * 改面越大越容易出错（见铁律/用户裁定「大改造成更多 bug」）。
     */
    private static String[] modeArgs(String[] args) {
        String[] shifted = new String[args.length + 1];
        shifted[0] = "ig";
        shifted[1] = "debug";
        System.arraycopy(args, 1, shifted, 2, args.length - 1);
        return shifted;
    }

    private boolean modeCmd(CommandSender sender, String[] args) {
        GameConfig cfg = plugin.gameConfig();

        if (args.length < 3) {
            sender.sendMessage(Component.text("─── 本局设置（运行时覆盖）───", NamedTextColor.GOLD));
            sender.sendMessage(Component.text(cfg.describeEffective(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text(
                    "/ig mode funds|ultimate|config ｜ bots <数量|off|config> ｜ infernal on|off|config ｜ reset",
                    NamedTextColor.DARK_GRAY));
            return true;
        }

        String what = args[2].toLowerCase(Locale.ROOT);
        String value = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "";
        switch (what) {
            case "funds", "basic" -> {
                cfg.setModeOverride(io.mczju.infernalguess.config.GameMode.BASIC);
                sender.sendMessage(ok("本局模式 → 资金模式（BASIC）"));
            }
            case "ultimate", "ult" -> {
                cfg.setModeOverride(io.mczju.infernalguess.config.GameMode.ULTIMATE);
                sender.sendMessage(ok("本局模式 → 终极对决模式（ULTIMATE，无资金概念）"));
            }
            case "bots", "bot" -> {
                if (value.isEmpty()) {
                    usage(sender, "/ig mode bots <数量|off|config>");
                    return true;
                }
                if (value.equals("off") || value.equals("0")) {
                    cfg.setBotsOverride(false, 0);
                    sender.sendMessage(ok("人机 → 关闭"));
                } else if (value.equals("config")) {
                    cfg.setBotsOverride(null, null);
                    sender.sendMessage(ok("人机 → 恢复配置值（" + cfg.botCount() + " 个）"));
                } else {
                    int n = parseInt(value, -1);
                    if (n < 0 || n > 20) {
                        sender.sendMessage(Component.text("人机数量应在 0~20", NamedTextColor.RED));
                        return true;
                    }
                    cfg.setBotsOverride(n > 0, n);
                    sender.sendMessage(ok("人机 → " + (n == 0 ? "关闭" : ("开启 " + n + " 个"))));
                }
            }
            // 词条总开关。`infernal` 保留为别名，兼容此前记住旧写法的使用者。
            case "affixes", "affix", "infernal" -> {
                if (value.isEmpty()) {
                    usage(sender, "/ig mode affixes <on|off|config>");
                    return true;
                }
                switch (value) {
                    case "on", "true", "1" -> {
                        cfg.setAffixesOverride(true);
                        sender.sendMessage(ok("词条 → 开启（当前已实现 "
                                + plugin.affixRegistry().size() + " 个词条）"));
                    }
                    case "off", "false", "0" -> {
                        cfg.setAffixesOverride(false);
                        sender.sendMessage(ok("词条 → 关闭（本局不会生成任何词条）"));
                    }
                    case "config" -> {
                        cfg.setAffixesOverride(null);
                        sender.sendMessage(ok("词条 → 恢复配置值"));
                    }
                    default -> usage(sender, "/ig mode affixes <on|off|config>");
                }
            }
            case "reset", "clear" -> {
                cfg.clearOverrides();
                sender.sendMessage(ok("已清空全部运行时覆盖，全部恢复用 config.yml 的值"));
            }
            case "config" -> {
                cfg.setModeOverride(null);
                sender.sendMessage(ok("模式 → 恢复配置值（" + cfg.mode() + "）"));
            }
            default -> usage(sender,
                    "/ig mode [funds|ultimate|config] [bots <数量|off|config>] [infernal on|off|config] [reset]");
        }
        if (!what.equals("reset") && !what.equals("clear")) {
            sender.sendMessage(Component.text("当前生效：" + cfg.describeEffective(),
                    NamedTextColor.DARK_GRAY));
        }
        return true;
    }

    private Component ok(String msg) {
        return Component.text("✔ " + msg, NamedTextColor.GREEN);
    }

    private void select(CommandSender sender, String name) {
        if (sender instanceof Player player) {
            selectedArena.put(player.getUniqueId(), name);
        }
    }

    private String current(CommandSender sender) {
        if (sender instanceof Player player) {
            String s = selectedArena.get(player.getUniqueId());
            if (s != null) {
                return s;
            }
        }
        // 退化：只有一个竞技场时直接用它
        var all = plugin.arenaConfig().arenas();
        return all.size() == 1 ? all.iterator().next().name() : null;
    }

    private void needSelect(CommandSender sender) {
        sender.sendMessage(Component.text("请先用 /ig arena select <名称> 选定竞技场。",
                NamedTextColor.YELLOW));
    }

    private void save(CommandSender sender) {
        try {
            plugin.arenaConfig().save(plugin);
        } catch (IOException e) {
            plugin.getLogger().severe("保存 arenas.yml 失败：" + e.getMessage());
            sender.sendMessage(Component.text("保存失败，详见控制台。", NamedTextColor.RED));
        }
    }

    private boolean notYet(CommandSender sender, String feature, String stage) {
        sender.sendMessage(Component.text("[IG] " + feature + " 尚未接入（属于 " + stage + "）。",
                NamedTextColor.YELLOW));
        return true;
    }

    private void usage(CommandSender sender, String text) {
        sender.sendMessage(Component.text("用法：" + text, NamedTextColor.YELLOW));
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("─── InfernalGuess（斗蛐蛐）───", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/ig info                     查看配置与每回合规则", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/ig preview <seed> [round]   打印剧本", NamedTextColor.GRAY));
        if (sender.hasPermission("infernalguess.admin")) {
            sender.sendMessage(Component.text("── 管理 ──", NamedTextColor.GOLD));
            sender.sendMessage(Component.text("/ig arena list|create|delete|rename|select|info", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("/ig arena edit [名称]        打开调试 UI（左键绑定点位，右键传送）", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("/ig arena setspawn <player|spectate>", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("/ig arena setpoint <left|right> <1|2|3>", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("/ig debug reload|preview <seed>", NamedTextColor.GRAY));
        }
    }

    // ================= Tab 补全 =================

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("info", "preview", "menu", "difficulty", "leave"));
            if (sender.hasPermission("infernalguess.admin")) {
                out.addAll(List.of("arena", "debug"));
            }
            return filter(out, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("arena") && sender.hasPermission("infernalguess.admin")) {
            if (args.length == 2) {
                return filter(List.of("list", "create", "delete", "rename", "select", "info",
                        "edit", "setspawn", "setpoint"), args[1]);
            }
            String action = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3) {
                if (action.equals("setspawn")) {
                    return filter(List.of("player", "spectate"), args[2]);
                }
                if (action.equals("setpoint")) {
                    return filter(List.of("left", "right"), args[2]);
                }
                return filter(arenaNames(), args[2]);
            }
            if (args.length == 4) {
                if (action.equals("setpoint")) {
                    return filter(List.of("1", "2", "3"), args[3]);
                }
                if (action.equals("rename")) {
                    return filter(arenaNames(), args[3]);
                }
            }
        }
        if (sub.equals("debug") && sender.hasPermission("infernalguess.admin") && args.length == 2) {
            return filter(List.of("reload", "preview", "mgc", "battle", "round", "mode", "room",
                    "where", "bet", "summon", "skip", "set", "info"), args[1]);
        }
        if (sub.equals("debug") && sender.hasPermission("infernalguess.admin")
                && args.length == 3 && args[1].equalsIgnoreCase("mode")) {
            return filter(List.of("funds", "ultimate", "bots", "affixes", "infernal", "config", "reset"), args[2]);
        }
        return List.of();
    }

    private List<String> arenaNames() {
        List<String> names = new ArrayList<>();
        for (ArenaConfig.Arena a : plugin.arenaConfig().arenas()) {
            names.add(a.name());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
