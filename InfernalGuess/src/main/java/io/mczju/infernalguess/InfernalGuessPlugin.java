package io.mczju.infernalguess;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import io.mczju.infernalguess.command.InfernalGuessCommand;
import io.mczju.infernalguess.config.ArenaConfig;
import io.mczju.infernalguess.config.ConfigError;
import io.mczju.infernalguess.config.DisplayConfig;
import io.mczju.infernalguess.config.GameConfig;
import io.mczju.infernalguess.config.MobGroupConfig;
import io.mczju.infernalguess.game.InfernalGuessGame;
import io.mczju.infernalguess.game.InfernalGuessPlayerData;
import com.github.mczjuops.mczjugamecore.game.room.GameRoomState;
import io.mczju.infernalguess.game.InfernalGuessRoom;
import io.mczju.infernalguess.gui.ArenaEditListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * InfernalGuess（斗蛐蛐）主类。
 *
 * <p>已完成：
 * <ul>
 *   <li>阶段 1：骨架与构建</li>
 *   <li>阶段 2：配置层（5 个文件 + 全量校验）</li>
 *   <li>阶段 3：剧本生成器（纯逻辑 + 单元测试）</li>
 *   <li>阶段 4：竞技场与调试指令 / 调试 UI</li>
 * </ul>
 *
 * <p>配置校验失败会**明确报出「文件 + 键路径」并禁用插件**，而不是带着坏配置继续运行。
 */
public final class InfernalGuessPlugin extends JavaPlugin {

    private static InfernalGuessPlugin instance;

    private GameConfig gameConfig;
    private MobGroupConfig mobGroupConfig;
    private DisplayConfig displayConfig;
    private ArenaConfig arenaConfig;
    /** 词条注册表（**本插件自有，不依赖任何外部插件**）。 */
    private io.mczju.infernalguess.affix.AffixRegistry affixRegistry;
    private io.mczju.infernalguess.item.MenuItemService menuItemService;
    private io.mczju.infernalguess.game.ScoreboardService scoreboardService;

    /** 当前进行中的对局（由 /mgc 或调试指令创建）。 */
    private volatile io.mczju.infernalguess.game.RoundManager activeRound;

    @Override
    public void onEnable() {
        instance = this;

        // ★ 必须**先于** loadAllConfigs() —— MobGroupConfig 要用它做词条 id 的加载期白名单校验
        //   （当前白名单为空 → 任何词条配置都会当场报错，而不是静默失效）
        this.affixRegistry = new io.mczju.infernalguess.affix.AffixRegistry(this);

        saveDefaultConfig();
        migrateShippedFiles();

        try {
            loadAllConfigs();
        } catch (ConfigError e) {
            getLogger().severe("配置加载失败，插件将被禁用。");
            getLogger().severe("  " + e.getMessage());
            getLogger().severe("  请修正 " + e.file() + " 后重启服务器（或 /ig debug reload）。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        logSummary();

        PluginCommand cmd = getCommand("ig");
        if (cmd != null) {
            InfernalGuessCommand executor = new InfernalGuessCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        } else {
            getLogger().warning("plugin.yml 里没有注册指令 ig，管理指令不可用。");
        }

        getServer().getPluginManager().registerEvents(new ArenaEditListener(this), this);
        getServer().getPluginManager().registerEvents(
                new io.mczju.infernalguess.battle.BattleListener(this), this);
        getServer().getPluginManager().registerEvents(
                new io.mczju.infernalguess.betting.BetMenuListener(), this);
        getServer().getPluginManager().registerEvents(
                new io.mczju.infernalguess.item.MenuItemListener(this), this);

        this.menuItemService = new io.mczju.infernalguess.item.MenuItemService(this);
        this.scoreboardService = new io.mczju.infernalguess.game.ScoreboardService(this);

        registerWithMgc();

        getLogger().info("InfernalGuess 已启用（回合流程 / 下注结算 / 记分板已接入）");
    }

    /**
     * 注册到 MCZJUGameCore。
     *
     * <p>失败**不禁用插件** —— 配置校验与 `/ig` 管理指令仍然可用，便于排查。
     */
    private void registerWithMgc() {
        try {
            MCZJUGameCore.getGameManager()
                    .registerGame(InfernalGuessGame.class, InfernalGuessRoom.class);
            MCZJUGameCore.getPlayerDataManager()
                    .registerPlayerData(InfernalGuessGame.ID, InfernalGuessPlayerData.class);
            ensureDefaultRoom();
            getLogger().info("已注册到 MCZJUGameCore：game id = " + InfernalGuessGame.ID);
        } catch (Throwable t) {
            getLogger().severe("注册到 MCZJUGameCore 失败：" + t);
            getLogger().severe("  配置与 /ig 管理指令仍可用，但 /mgc join 不可用。");
        }
    }

    /**
     * 确保至少存在一个房间实例。
     *
     * <p>Maggoteers 的实测教训（G1）：{@code registerGame} 只注册**类**；
     * {@code /mgc join} 还需要一个 {@code READY} 的**房间实例**，
     * 否则会返回 null 并提示「无空闲房间」。
     */
    private void ensureDefaultRoom() {
        var mgr = MCZJUGameCore.getGameRoomManager();
        var rooms = mgr.getGameRooms(InfernalGuessGame.ID);
        if (rooms != null && !rooms.isEmpty()) {
            getLogger().info("已存在 " + rooms.size() + " 个房间实例");
            // ★ 「房间存在」不等于「能加入」：MGC 判定空闲的条件是
            //   state == READY（已用字节码核实 getRandomLeisureGameRoom 的过滤条件）。
            //   房间 state **不落盘**，所以开机时通常都是 READY；
            //   这里只是防御性检查 —— 万一不是，直接把话说明白，省得又去猜「无空闲房间」。
            long ready = rooms.stream().filter(r -> r.getState() == GameRoomState.READY).count();
            if (ready == 0) {
                getLogger().warning("⚠ 房间有 " + rooms.size()
                        + " 个，但**没有一个处于 READY** → /mgc join 会提示「无空闲房间」。");
                getLogger().warning("  可执行 /ig debug room reset 立即复位（不必重启）。");
            } else {
                getLogger().info("其中 READY（可加入）的房间：" + ready + " 个");
            }
            return;
        }
        var room = mgr.createGameRoom(InfernalGuessGame.ID, "default");
        if (room != null) {
            // 顺便把第一个可用竞技场绑定到房间，省去运维手配
            if (room instanceof InfernalGuessRoom r) {
                var first = arenaConfig.playable().stream().findFirst().orElse(null);
                if (first != null) {
                    r.arenaName = first.name();
                    getLogger().info("默认房间已绑定竞技场 `" + first.name() + "`");
                }
            }
            mgr.saveGameRoom(InfernalGuessGame.ID);
            getLogger().info("已创建默认房间 `default`（供 /mgc join 使用）");
        } else {
            getLogger().warning("创建默认房间失败，/mgc join 可能提示「无空闲房间」。");
            getLogger().warning("  可尝试：/mgcop room create " + InfernalGuessGame.ID + " default");
        }
    }

    /** 取本游戏的第一个房间（供调试指令读写房间字段）。 */
    public io.mczju.infernalguess.game.InfernalGuessRoom firstRoom() {
        var rooms = MCZJUGameCore.getGameRoomManager().getGameRooms(InfernalGuessGame.ID);
        if (rooms == null) {
            return null;
        }
        for (var r : rooms) {
            if (r instanceof io.mczju.infernalguess.game.InfernalGuessRoom igRoom) {
                return igRoom;
            }
        }
        return null;
    }

    @Override
    public void onDisable() {
        getLogger().info("InfernalGuess 已禁用");
    }

    public static InfernalGuessPlugin getInstance() {
        return instance;
    }

    // ================= 配置 =================

    /** 读取并校验全部配置文件。任何一项非法都抛 {@link ConfigError}。 */
    private void loadAllConfigs() {
        reloadConfig(); // 重新从磁盘读 config.yml
        this.gameConfig = GameConfig.load(getConfig());
        this.mobGroupConfig = MobGroupConfig.load(this);
        this.displayConfig = DisplayConfig.load(this);
        this.arenaConfig = ArenaConfig.load(this);
    }

    /**
     * 把出厂配置里**新增**的键补进玩家磁盘上的同名文件。
     *
     * <h2>为什么必须有这一步</h2>
     * 本插件采用**严格 schema 校验**（缺键即报错禁用，见 {@code ConfigReader} 与
     * {@code DisplayConfig} 的必需键清单）—— 这是相对 Maggoteers「静默读成 0.0」的改进。
     * 但严格校验有个必然后果：**升级后新增的键在老存档里不存在**，插件会直接拒绝启动。
     *
     * <p>实测踩到两次：
     * <ol>
     *   <li>加了 {@code battle.max-fight-seconds} → 旧的 config.yml 缺键 → 插件自我禁用</li>
     *   <li>加了 {@code icons.menu.bet-item} 并列入必需图标 → 旧的 display.yml 缺键 → 同样会挂</li>
     * </ol>
     * 所以迁移必须覆盖**全部随包发布的配置文件**，不能只做 config.yml。
     *
     * <p>本方法只**新增**缺失的键，**绝不覆盖**任何已有值（玩家的改动一律保留），
     * 并把补进去的键名打印出来，方便运维核对。
     *
     * <p>{@code arenas.yml} **不在**迁移范围内 —— 它是纯玩家数据，出厂时为空。
     */
    private void migrateShippedFiles() {
        // ★ 先做一次性的「移除炒鸡词条」清理，再走通用的补键迁移。
        //   顺序很重要：老存档的 mob-groups.yml 里还有词条，而词条白名单现在为空，
        //   不清掉的话加载期会直接拒绝启动（这正是升级时真实踩到的坑）。
        stripLegacyAffixes();

        for (String name : new String[]{"config.yml", "display.yml", "mob-groups.yml"}) {
            try {
                migrateOne(name);
            } catch (Exception e) {
                getLogger().warning(name + " 迁移检查失败（不影响启动）：" + e);
            }
        }

        // ★ wave-pools.yml 已在重写中废弃（怪物池改为「所有怪物组同一个大池」）。
        //   老存档里会残留这一份文件。**不去动用户的文件**（他可能自己改过），只提示。
        if (new java.io.File(getDataFolder(), "wave-pools.yml").isFile()) {
            getLogger().info("检测到 wave-pools.yml —— 该文件已废弃："
                    + "怪物池改为「所有怪物组同属一个大池」，抽取只看回合权重表，"
                    + "本文件不再被读取，可以放心删除。");
        }
    }

    /**
     * 一次性清理：把 {@code mob-groups.yml} 里**当前未实现**的词条条目删掉。
     *
     * <h2>为什么需要它</h2>
     * 2026-10-11 移除了炒鸡 API 依赖，词条改为自有体系且**白名单当时为空**。
     * 老存档的 {@code mob-groups.yml} 里仍有 92 条炒鸡词条，
     * 而加载期白名单会一律拒绝 → **插件拒绝启动**（实测确认过这个失败模式）。
     *
     * <h2>★ 但它只应删「未实现的」，不能无条件清空</h2>
     * 第一版写成「见到 {@code infernal_affixes} 就整段删掉」——
     * 在自研词条系统上线后**把我方自己配置的词条也一起吃了**
     * （实测：加好的 5 条在启动瞬间被清光，表现为「名牌上没有词条」）。
     *
     * <p>现在的语义：**只删 id 不在 {@code AffixRegistry.knownIds()} 里的条目**，
     * 已实现的条目原样保留。这样它同时也是**自愈**的 ——
     * 每当自研词条多实现一个，老配置里对应的条目就会自动变得可用。
     *
     * <p>清理前会**备份**一份 {@code mob-groups.yml.bak}。只在真的删掉了东西时才写盘，幂等。
     */
    private void stripLegacyAffixes() {
        java.io.File target = new java.io.File(getDataFolder(), "mob-groups.yml");
        if (!target.isFile()) {
            return;
        }
        try {
            var disk = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(target);
            var root = disk.getConfigurationSection("mob_groups");
            if (root == null) {
                return;
            }
            java.util.Set<String> known = affixRegistry.knownIds();
            java.util.List<String> touched = new java.util.ArrayList<>();
            int removedEntries = 0;

            for (String id : root.getKeys(false)) {
                var s = root.getConfigurationSection(id);
                if (s == null || !s.isSet("infernal_affixes")) {
                    continue;
                }
                java.util.List<java.util.Map<?, ?>> raw = s.getMapList("infernal_affixes");
                java.util.List<java.util.Map<?, ?>> keep = new java.util.ArrayList<>();
                int dropped = 0;
                for (java.util.Map<?, ?> m : raw) {
                    Object rawId = m.get("id");
                    String sid = rawId == null
                            ? "" : String.valueOf(rawId).toLowerCase(java.util.Locale.ROOT);
                    if (known.contains(sid)) {
                        keep.add(m);      // ★ 已实现 → 保留（我们自己的词条就靠这条活下来）
                    } else {
                        dropped++;        // 未实现 → 会让加载期拒绝启动，必须删
                    }
                }
                if (dropped == 0) {
                    continue;
                }
                removedEntries += dropped;
                if (keep.isEmpty()) {
                    s.set("infernal_affixes", null);
                } else {
                    s.set("infernal_affixes", keep);
                }
                touched.add(id + "(-" + dropped + ")");
            }
            if (touched.isEmpty()) {
                return;   // 已经干净了
            }
            java.io.File backup = new java.io.File(getDataFolder(), "mob-groups.yml.bak");
            try {
                java.nio.file.Files.copy(target.toPath(), backup.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                getLogger().warning("备份 mob-groups.yml 失败（仍会继续清理）：" + e);
            }
            disk.save(target);
            getLogger().warning("mob-groups.yml 里有 " + touched.size() + " 组、共 "
                    + removedEntries + " 条词条**当前未实现**，已删除："
                    + String.join(", ", touched));
            getLogger().warning("  原因：词条 id 必须在 AffixRegistry 里已实现，"
                    + "否则加载期会拒绝启动（当前已实现：" + known.size() + " 个）。");
            getLogger().warning("  清理前的原文件已备份为 mob-groups.yml.bak。");
        } catch (Exception e) {
            getLogger().warning("清理未实现词条失败：" + e);
        }
    }

    /**
     * 需要**以出厂值为准**的文件（游戏内容，我方拥有）。
     *
     * <p>其余文件（{@code config.yml} / {@code display.yml}）是用户的调参入口，
     * 继续「只增不改」，绝不覆盖用户改过的值。
     */
    private static final java.util.Set<String> CONTENT_FILES =
            java.util.Set.of("mob-groups.yml");

    private void migrateOne(String name) throws java.io.IOException {
        java.io.File target = new java.io.File(getDataFolder(), name);
        if (!target.isFile()) {
            // 不存在 → 由各自的加载器写出完整出厂文件（无需迁移）
            return;
        }
        try (java.io.InputStream in = getResource(name)) {
            if (in == null) {
                getLogger().warning("jar 内找不到出厂文件 " + name + "，跳过迁移。");
                return;
            }
            var defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            var disk = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(target);

            boolean content = CONTENT_FILES.contains(name);
            java.util.List<String> pruned = content
                    ? io.mczju.infernalguess.config.ConfigMerge.pruneObsolete(defaults, disk)
                    : java.util.List.of();
            java.util.List<String> added = content
                    ? io.mczju.infernalguess.config.ConfigMerge.overwriteShared(defaults, disk)
                    : io.mczju.infernalguess.config.ConfigMerge.merge(defaults, disk);

            if (!pruned.isEmpty()) {
                getLogger().warning(name + " 里有 " + pruned.size()
                        + " 个**出厂已废弃**的键，已按出厂结构删除："
                        + String.join(", ", pruned));
                getLogger().warning("  原因见 dev-log 059：数量改由「回合目标 ÷ 单只权重」算出，"
                        + "旧的 amount.min/max 会把数量悄悄按死在很小的值上。");
            }

            if (!added.isEmpty() || !pruned.isEmpty()) {
                if (content) {
                    // 内容文件会覆盖用户改过的值 → 先备份，并在日志里说清楚
                    java.io.File backup = new java.io.File(getDataFolder(), name + ".bak");
                    try {
                        java.nio.file.Files.copy(target.toPath(), backup.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception e) {
                        getLogger().warning("备份 " + name + " 失败（仍会继续升级）：" + e);
                    }
                }
                disk.save(target);
                getLogger().info(name + (content ? " 已按出厂值升级 " : " 已补齐 ")
                        + added.size() + " 个键"
                        + (pruned.isEmpty() ? "" : "、删除 " + pruned.size() + " 个废弃键")
                        + (content ? "（游戏内容以出厂值为准；原文件已备份为 " + name + ".bak）"
                        : "（保留你原有的值）")
                        + "：" + String.join(", ", added));
            }
        }
    }

    /** {@code /ig debug reload}：热重载全部配置文件。校验失败时抛出，调用方负责提示。 */
    public void reloadAllConfigs() {
        loadAllConfigs();
        logSummary();
    }

    private void logSummary() {
        getLogger().info("配置校验通过：" + gameConfig.rounds() + " 回合 / "
                + gameConfig.minPlayers() + "~" + gameConfig.maxPlayers() + " 人 / 模式 "
                + gameConfig.mode() + " / 词条 " + (gameConfig.affixesEnabled() ? "启用" : "关闭")
                + "（已实现词条 " + affixRegistry.size() + " 个）");
        getLogger().info("怪物组校验通过：" + mobGroupConfig.size() + " 组（同一大池）");
        getLogger().info("显示配置校验通过：生物代表物品 " + displayConfig.mobItems().size() + " 项");
        getLogger().info("竞技场配置：共 " + arenaConfig.arenas().size()
                + " 个，其中可用 " + arenaConfig.playable().size() + " 个");

        // 竞技场所在世界必须存在（用户第 6 条：竞技场建在固定世界里，插件不生成新世界）
        String configuredWorld = gameConfig.arenaWorld();
        if (getServer().getWorld(configuredWorld) == null) {
            getLogger().warning("config.yml 的 arena.world = `" + configuredWorld
                    + "` 当前**不是一个已加载的世界**。");
            getLogger().warning("  竞技场必须建在该世界里（插件不会替你生成世界）。");
            getLogger().warning("  请先创建该世界，或把 arena.world 改成已存在的世界名。");
        }

        // 词条表非零但词条系统没接入 → 权重表会与实际战力不符，必须提示
        if (gameConfig.hasAffixTableButAffixesUnavailable()) {
            getLogger().warning("enable-affixes 为 false（或没有已实现的词条），"
                    + "因此**本局不会生成任何词条**；affixes.count-by-round 已自动按全 0 处理，"
                    + "以保证权重与实际战力一致。");
        }

        checkPoolFeasibility();
    }

    /**
     * 启动期**权重可达性自检**：逐回合探测「最好能凑到多接近本回合的目标权重」。
     *
     * <h2>为什么需要它</h2>
     * 重写后难度**只**由 {@code config.yml} 的 {@code weight-by-round} 与
     * {@code mob-groups.yml} 的 {@code weight} 共同决定（数量由分配器推导）。
     * 若某些回合的目标权重与怪物权重表不匹配（例如目标 3 而池里最轻的是 1.5），
     * 分配器再聪明也凑不出目标，表现是「双方权重都偏一大截、或某一侧只有 1 种怪」——
     * 且**不会报任何错**。
     *
     * <p>探测口径见 {@link io.mczju.infernalguess.run.RunPlanner#probeFeasibility}：
     * 乐观（不读 amount.max、不含词条加成）。因此这里**只报告、不失败**，
     * 越过用户设定的 {@code weight-tolerance}（默认 2%）就告警，由人决定怎么调配置。
     */
    private void checkPoolFeasibility() {
        double tolerance = gameConfig.weightTolerance();
        var rows = io.mczju.infernalguess.run.RunPlanner.probeFeasibility(
                gameConfig, mobGroupConfig.unitWeights());

        getLogger().info("权重可达性自检（一个大池 " + mobGroupConfig.size()
                + " 组 + 回合权重表，容差 ±" + String.format(java.util.Locale.ROOT, "%.0f%%",
                tolerance * 100) + "）：\n"
                + io.mczju.infernalguess.run.RunPlanner.describeFeasibility(
                rows, tolerance, mobGroupConfig.size()));

        int bad = 0;
        for (var f : rows) {
            if (f.ratio() > tolerance) {
                bad++;
            }
        }
        if (bad > 0) {
            getLogger().warning("有 " + bad + " 个回合**即便抽到最合适的三种怪**也凑不到 ±"
                    + String.format(java.util.Locale.ROOT, "%.0f%%", tolerance * 100)
                    + " 以内 —— 该回合的双方权重会整体偏离标准。");
            getLogger().warning("  请调整 config.yml 的 weight-by-round，"
                    + "或调整 mob-groups.yml 的 weight（两者同量纲）。");
        }
    }

    // ================= 访问器 =================

    /** 已校验的主配置。 */
    public GameConfig gameConfig() {
        return gameConfig;
    }

    /** 已校验的怪物组配置。 */
    public MobGroupConfig mobGroupConfig() {
        return mobGroupConfig;
    }

    /** 已校验的显示配置（文案 / 图标 / 生物代表物品）。 */
    public DisplayConfig displayConfig() {
        return displayConfig;
    }

    /** 竞技场配置（由调试指令维护）。 */
    public ArenaConfig arenaConfig() {
        return arenaConfig;
    }

    /** 词条注册表（自有实现；不可用时自动降级，永不返回 null）。 */
    public io.mczju.infernalguess.affix.AffixRegistry affixRegistry() {
        return affixRegistry;
    }

    /** 下注器菜单道具服务。 */
    public io.mczju.infernalguess.item.MenuItemService menuItemService() {
        return menuItemService;
    }

    /** 记分板服务。 */
    public io.mczju.infernalguess.game.ScoreboardService scoreboardService() {
        return scoreboardService;
    }

    /** 当前进行中的对局。 */
    public java.util.Optional<io.mczju.infernalguess.game.RoundManager> activeRound() {
        return java.util.Optional.ofNullable(activeRound);
    }

    /** 设置/清除当前对局。 */
    public void setActiveRound(io.mczju.infernalguess.game.RoundManager round) {
        this.activeRound = round;
    }
}
