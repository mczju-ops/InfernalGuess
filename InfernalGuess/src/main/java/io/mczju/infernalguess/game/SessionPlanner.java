package io.mczju.infernalguess.game;

import io.mczju.infernalguess.config.ArenaConfig;
import io.mczju.infernalguess.config.GameConfig;
import io.mczju.infernalguess.config.GameMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 「这一局到底能不能开、用哪个竞技场、都有谁参与」的**纯粹决策逻辑**。
 *
 * <h2>为什么单独抽一个类</h2>
 * 真人对局路径（{@code /mgc join}）需要客户端才能端到端验证，
 * 但其中**最容易出错**的正是这段：竞技场选择与参与者组装。
 * 抽成不依赖 Bukkit 运行时的纯逻辑后，就能用单元测试覆盖，
 * 而不是只能靠「开一次服务器试试」。
 */
public final class SessionPlanner {

    private SessionPlanner() {
    }

    /** 一名参与者（真人来自 {@code PlayerExt}，人机由配置生成）。 */
    public record Participant(UUID uuid, String name, boolean bot) {
    }

    /** 一局的组装结果。 */
    public record SessionPlan(ArenaConfig.Arena arena, List<GamePlayer> players) {
    }

    /**
     * 选择竞技场。
     *
     * <p>优先级：**房间指定且完整** → **从可用竞技场里随机挑一个** → 空。
     *
     * <p>用户需求第 2 条原文：「随机选择一个**对称竞技场**」——
     * 所以多个可用竞技场时是**随机**而非取第一个。
     * 随机由本局种子派生，因此**同一局内可复现**（便于复盘与调试）。
     *
     * @param roomArenaName 房间上配置的竞技场名，可为 {@code null}
     */
    public static Optional<ArenaConfig.Arena> selectArena(ArenaConfig arenas, String roomArenaName) {
        return selectArena(arenas, roomArenaName, 0L);
    }

    /** 带种子的竞技场选择（同种子结果相同）。 */
    public static Optional<ArenaConfig.Arena> selectArena(ArenaConfig arenas,
                                                          String roomArenaName, long seed) {
        if (arenas == null) {
            return Optional.empty();
        }
        if (roomArenaName != null && !roomArenaName.isBlank()) {
            var byRoom = arenas.get(roomArenaName).orElse(null);
            if (byRoom != null && byRoom.isComplete()) {
                return Optional.of(byRoom);
            }
        }
        List<ArenaConfig.Arena> playable = arenas.playable();
        if (playable.isEmpty()) {
            return Optional.empty();
        }
        if (playable.size() == 1) {
            return Optional.of(playable.get(0));
        }
        // ★ 随机选择（用户第 2 条）；派生自本局种子，可复现
        int idx = new io.mczju.infernalguess.run.SeededRng(seed)
                .derive(7_777_777L)
                .nextInt(playable.size());
        return Optional.of(playable.get(idx));
    }

    /**
     * 组装一局。
     *
     * @param realPlayers 真人参与者（顺序即加入顺序）
     * @throws IllegalStateException 没有可用竞技场
     */
    public static SessionPlan plan(ArenaConfig arenas, GameConfig cfg,
                                   String roomArenaName, List<Participant> realPlayers) {
        return plan(arenas, cfg, roomArenaName, realPlayers, 0L);
    }

    /** 组装一局（带本局种子，用于随机挑竞技场）。 */
    public static SessionPlan plan(ArenaConfig arenas, GameConfig cfg, String roomArenaName,
                                   List<Participant> realPlayers, long seed) {
        ArenaConfig.Arena arena = selectArena(arenas, roomArenaName, seed).orElseThrow(
                () -> new IllegalStateException(
                        "没有可用的竞技场。请用 /ig arena create <名称> 并用调试 UI 绑定足够点位。"));

        List<GamePlayer> players = new ArrayList<>();
        for (Participant p : realPlayers) {
            players.add(new GamePlayer(p.uuid(), p.name(), false, cfg.mode(),
                    cfg.initialFunds(), cfg.ultimateLife(), cfg.ultimateShield()));
        }
        // 人机补位：占用真实名额并参与排名（Q21）
        if (cfg.botEnabled()) {
            List<String> names = cfg.botNames();
            for (int i = 0; i < cfg.botCount(); i++) {
                String name = i < names.size() ? names.get(i) : ("[AI]" + (i + 1));
                // 名字 + 竞技场名派生稳定 UUID，保证同一局内唯一且可复现
                UUID id = UUID.nameUUIDFromBytes((name + "|" + arena.name()).getBytes());
                players.add(new GamePlayer(id, name, true, cfg.mode(),
                        cfg.initialFunds(), cfg.ultimateLife(), cfg.ultimateShield()));
            }
        }
        return new SessionPlan(arena, players);
    }

    // ================= 测试辅助 =================

    /** 造一个完整的竞技场（3 点/侧 + 双落点），供测试使用。 */
    public static ArenaConfig.Arena completeArena(String name, String world) {
        Map<Integer, ArenaConfig.Point> left = new LinkedHashMap<>();
        Map<Integer, ArenaConfig.Point> right = new LinkedHashMap<>();
        for (int i = 1; i <= ArenaConfig.POINTS_PER_SIDE; i++) {
            left.put(i, new ArenaConfig.Point(i == 1 ? 0 : 10, 64, i * 10));
            right.put(i, new ArenaConfig.Point(i == 1 ? 100 : 110, 64, i * 10));
        }
        return new ArenaConfig.Arena(name, world,
                new ArenaConfig.Spawn(50, 70, 50, 0, 0),
                new ArenaConfig.Spawn(50, 90, 50, 0, 20),
                left, right);
    }

    /** 造一个**不完整**的竞技场（缺刷怪点），供测试使用。 */
    public static ArenaConfig.Arena incompleteArena(String name, String world) {
        return new ArenaConfig.Arena(name, world, null, null, Map.of(), Map.of());
    }

    /** 造一份够用的配置视图（只用于测试）。 */
    public static GameConfig testConfig(GameMode mode, boolean botEnabled, int botCount) {
        return testConfig(mode, botEnabled, botCount, null);
    }

    /**
     * 同上，但可**显式指定人机名字表**。
     *
     * <h2>为什么需要这个重载</h2>
     * 「名字不够用时自动补 {@code [AI]n}」这条兜底逻辑，只有在
     * **机器人数 &gt; 名字条数** 时才会触发。
     * 而 {@code config.yml} 的 {@code bot.names} 是**用户随时会改**的内容
     * （2026-10-12 实测：用户把名单从 3 个扩到 9 个，于是原来那条
     * 「要 5 个人机、断言出现 [AI]4/[AI]5」的测试当场失效）。
     *
     * <p>把名字表**注入**进来，测试就不再随出厂名单的条数漂移 ——
     * 它要验的是**兜底规则**，不是「出厂配了几个名字」。
     *
     * @param botNames 人机名字表；{@code null} 表示用 config.yml 里的
     */
    public static GameConfig testConfig(GameMode mode, boolean botEnabled, int botCount,
                                       List<String> botNames) {
        org.bukkit.configuration.file.YamlConfiguration yml =
                org.bukkit.configuration.file.YamlConfiguration
                        .loadConfiguration(
                                new java.io.File("src/main/resources/config.yml"));
        yml.set("mode", mode.name());
        yml.set("bot.enabled", botEnabled);
        yml.set("bot.bots", botCount);
        if (botNames != null) {
            yml.set("bot.names", botNames);
        }
        return GameConfig.load(yml);
    }
}
