package io.mczju.infernalguess.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.game.GameMeta;
import com.github.mczjuops.mczjugamecore.game.strategy.wait.DefaultGameWaitStrategy;
import com.github.mczjuops.mczjugamecore.game.strategy.wait.GameWaitStrategy;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerDeathStrategy;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerQuitStrategy;
import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.config.ArenaConfig;
import io.mczju.infernalguess.config.GameConfig;
import org.bukkit.Material;

import java.util.List;

/**
 * 对局主类：MGC 的 {@link AbstractGame} 子类。
 *
 * <p>注意两点（都来自实测）：
 * <ul>
 *   <li>**必须有无参构造** —— MGC 通过反射实例化（{@code createGame}），
 *       因此插件实例走 {@link InfernalGuessPlugin#getInstance()}（Maggoteers 的验证做法）</li>
 *   <li>{@code Sender#info/warn/error} 只接受 **String**（MiniMessage 文本），不是 Component</li>
 * </ul>
 *
 * <p>阶段 5 只负责「能被 MGC 正确注册与开局」；真正的回合流程在阶段 6~7 接入。
 */
public final class InfernalGuessGame extends AbstractGame {

    /** MGC 的游戏 id（全小写）。 */
    public static final String ID = "infernalguess";

    /** 清理幂等保护：{@code abortGame} 与 {@code endGame} 都可能走到清理。 */
    private volatile boolean cleanedUp = false;

    /**
     * 本局 {@link WorldSetup#apply} 返回的**世界设置快照**（对局结束时还原用）。
     *
     * <p>改动的是世界级设置（难度 / 游戏规则 / 时间），必须能还原 —— 竞技场常常就在主世界。
     */
    private volatile WorldSetup.Snapshot worldSnapshot;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public GameMeta getGameMeta() {
        var display = plugin().displayConfig();
        return GameMeta.builder()
                .displayName(display.raw("game.meta-name"))
                .icon(Material.CLOCK)
                .author(display.raw("game.meta-author"))
                .description(display.raw("game.meta-description").lines().toList())
                .build();
    }

    /**
     * ★ 等待策略：**加入房间时把玩家送到房间配置的集合点**。
     *
     * <p>MGC 的游戏生命周期里**没有 {@code onPlayerJoin}** 这个回调
     * （{@code AbstractGame} 实测只有 gameInit/gameStart/cancelGame/abortGame/endGame），
     * 玩家入局的挂钩点就在{@link com.github.mczjuops.mczjugamecore.game.strategy.wait.GameWaitStrategy}
     * 上 —— 这与 Maggoteers 的做法一致（它的 {@code getGameWaitStrategy()} 就是覆写这里）。
     *
     * <p>同时记录玩家**入局前的位置**，作为局末回程的回退链第三环
     * （房间没配 return/wait 时不至于把玩家丢在主世界出生点）。
     *
     * <p>★ 2026-10-13 追加：这里同时负责**开局人数门槛**（见 {@link StartGate}）——
     * 因为 MGC 的 {@code tryStart()} 对人数不足**只 warn、不清房间**，
     * 正是用户要的行为（「只会弹警告，不会清空等待房间」）；我们只需把**判据**换掉。
     */
    @Override
    public GameWaitStrategy getGameWaitStrategy() {
        GameConfig g = plugin().gameConfig();
        return new DefaultGameWaitStrategy(this, g.maxPlayers(), g.minPlayers()) {
            @Override
            public boolean onPlayerJoin(PlayerExt player) {
                boolean ok = super.onPlayerJoin(player);
                if (ok && player != null && player.player() != null) {
                    InfernalGuessRoom.rememberJoinLocation(joinLocations, player.player());
                    InfernalGuessRoom.teleportWait(InfernalGuessGame.this, player.player());
                }
                return ok;
            }

            @Override
            public boolean onPartyJoin(com.github.mczjuops.mczjugamecore.player.party.Party party) {
                boolean ok = super.onPartyJoin(party);
                if (ok && party != null) {
                    party.getAllPlayer().forEach(p -> {
                        if (p != null && p.player() != null) {
                            InfernalGuessRoom.rememberJoinLocation(joinLocations, p.player());
                            InfernalGuessRoom.teleportWait(InfernalGuessGame.this, p.player());
                        }
                    });
                }
                return ok;
            }

            /**
             * ★ 开局判据 = {@link StartGate}（真人 2 名，**或**电脑玩家 &gt; 0）。
             *
             * <p>人数不够时**只警告**：不调 {@code startGame()}、也不调 {@code cancelGame()}
             * ⇒ 玩家留在等待房间里，可以继续等人、或用 {@code /ig mode bots <数量>} 临时开电脑玩家。
             *
             * <p>为什么这个覆写点是对的：MGC 里触发开局的入口（{@code MGCCommand} 的开始按钮、
             * 房间满员自动开）最终都走到 {@code GameWaitStrategy.tryStart()} /
             * {@code startGame()}，而 {@code AbstractGame#getGameWaitStrategy()} 是
             * **子插件必须实现的抽象方法** ⇒ 这里是唯一权威的入口。
             */
            @Override
            public void tryStart() {
                GameConfig c = plugin().gameConfig();
                int humans = getPlayers().size();
                if (!StartGate.canStart(humans, c.botEnabled(), c.botCount())) {
                    sender().warn(StartGate.blockedMessage(humans, c.botEnabled(), c.botCount()));
                    plugin().getLogger().info("[IG] 开局被拦下（房间保留）：真人 " + humans
                            + " 名，电脑玩家 enabled=" + c.botEnabled() + " count=" + c.botCount());
                    return;
                }
                super.tryStart();
            }
        };
    }

    /**
     * 玩家**入局前**的位置，供局末回程兜底（房间未配 return/wait 时用）。
     *
     * <p>包内可见：{@link InfernalGuessQuitStrategy} 单人退出时也要走同一条回程。
     */
    final java.util.Map<java.util.UUID, org.bukkit.Location> joinLocations =
            new java.util.HashMap<>();

    /** 取某玩家入局前的位置（没有记录时返回 {@code null}）。 */
    org.bukkit.Location joinLocationOf(java.util.UUID id) {
        return id == null ? null : joinLocations.get(id);
    }

    @Override
    public AbstractPlayerQuitStrategy getPlayerQuitStrategy() {
        return new InfernalGuessQuitStrategy(this);
    }

    @Override
    public AbstractPlayerDeathStrategy getPlayerDeathStrategy() {
        return new InfernalGuessDeathStrategy(this);
    }

    // ---------------- MGC 生命周期 ----------------

    @Override
    protected boolean onGameInit() {
        logger.info("[IG] 对局初始化（等待玩家加入）");
        return true;
    }

    @Override
    protected void onGameStart() {
        // ⚠️ 本方法不能阻塞（MGC 调完立即置 RUNNING）；
        //    因此开局组装放在下一个 tick，失败时能干净地中止对局。
        logger.info("[IG] 对局开始｜玩家 " + getPlayers().size() + " 人");
        plugin().getServer().getScheduler().runTask(plugin(), () -> {
            try {
                startSession();
            } catch (RuntimeException e) {
                logger.error("[IG] 开局失败：" + e.getMessage());
                sender().error("<red>开局失败：" + e.getMessage());
                abortViaManager();   // ★ 必须走 GameManager，否则房间不会被释放
            }
        });
    }

    /** 组装并启动一局：选竞技场 → 世界设置 → 组参与者（真人 + 人机）→ 传送/发道具 → 开跑。 */
    private void startSession() {
        InfernalGuessPlugin plug = plugin();
        var cfg = plug.gameConfig();
        var arenas = plug.arenaConfig();

        // ---------- -1. ★ 先清掉「上一场没清干净」的战斗 ----------
        //   症状（用户 2026-10-13 报「使用菜单后开局没反应 / 有清不掉的实体」）：
        //   如果上一场战斗没走 `cleanup()`（典型：调试开了 `ig debug battle start` 后忘了
        //   `stop`；或对局被异常方式结束），`BattleService.current` 仍指着它，
        //   于是本局第一次 `battle.prepare()` 会抛「已有一场战斗在进行」⇒ 开局失败 ⇒
        //   `abortViaManager` 释放房间 ⇒ 玩家看到的就是「点了开始没反应」。
        //   ★ 判据放在这里最准：此刻**还没有本局的 RoundManager**（activeRound 为空），
        //     `current` 里残留的必然是上一场的。有对局真在跑时不会走到这里（房间是 IN_GAME）。
        io.mczju.infernalguess.battle.BattleService.current().ifPresent(stale -> {
            if (!stale.isCleaned()) {
                plug.getLogger().warning("[IG] 开局前发现上一场战斗没清干净（第 "
                        + stale.round() + " 回合）→ 先强制清理，再开新对局");
                stale.cleanup();
            }
        });

        // ---------- 0. ★ 开局门槛（1.0.1 用户规则）----------
        //   「现在不可以单人开局，游戏开始时至少需要满足以下条件之一：
        //     有 2 名玩家；开启电脑玩家。」
        //
        //   为什么写在这里、而不是把 MGC 的 `min-players` 改成 2：
        //   MGC 只数**真人**（人机是我们在 SessionPlanner 里按 bot 配置补进去的，
        //   占的是真实名额），所以「1 真人 + N 人机」在 MGC 眼里永远是 1 人 ——
        //   min-players=2 会把「开了电脑玩家的单人局」一起拦掉。
        //   判据本身在 StartGate（纯函数 + 单测）。
        //
        //   ★ 2026-10-13 起：**正常路径根本走不到这里** ——
        //   `getGameWaitStrategy().tryStart()` 会先用同一条判据拦下并**只警告**
        //   （房间保留，见那边的注释）。这里保留为**兜底**：万一有别的路径把对局
        //   启动了（例如房间 max-players 被配成 1、玩家一进房就"满员"自动开），
        //   1 真人 0 人机的对局是**没法打的**（没有对手、没有兵），只能中止。
        int humanCount = getPlayers().size();
        if (!StartGate.canStart(humanCount, cfg.botEnabled(), cfg.botCount())) {
            throw new IllegalStateException(
                    StartGate.blockedMessage(humanCount, cfg.botEnabled(), cfg.botCount()));
        }

        // ---------- 1+3. 选竞技场 + 组参与者（纯逻辑，见 SessionPlanner，有单元测试覆盖）----------
        // 本局种子先生成，因为它同时决定「随机挑哪个竞技场」与整个剧本
        long seed = new java.util.Random().nextLong();
        // ⚠️ 1.0.1 起**不再把房间的 arenaName 传进去**：用户要求「多竞技场时随机」，
        //    而房间优先会让「房间↔竞技场一对一」的配置退化成固定顺序（见 SessionPlanner.selectArena）。
        var realPlayers = new java.util.ArrayList<SessionPlanner.Participant>();
        for (var pe : getPlayers()) {
            realPlayers.add(new SessionPlanner.Participant(
                    pe.getUniqueId(), pe.getName(), false));
        }
        SessionPlanner.SessionPlan plan = SessionPlanner.plan(
                arenas, cfg, realPlayers, seed);
        ArenaConfig.Arena arena = plan.arena();
        java.util.List<GamePlayer> players = plan.players();

        var world = ArenaConfig.resolveWorld(arena).orElse(null);
        if (world == null) {
            throw new IllegalStateException("竞技场世界 `" + arena.world()
                    + "` 未加载（插件不会替你生成世界）。");
        }
        logger.info("[IG] 使用竞技场 " + arena.name() + "（世界 " + world.getName()
                + "）｜ 参与者 " + players.size() + " 人（人机 "
                + players.stream().filter(GamePlayer::isBot).count() + "）");

        // ---------- 2. 世界设置 ----------
        // ★ 返回值是「应用前的世界状态快照」—— 对局结束时必须还原（见 cleanup）。
        //   这些设置是**世界级**的，而竞技场常常就在主世界（现场实测），
        //   只写不还 = 子插件在主世界留下永久副作用。
        worldSnapshot = WorldSetup.apply(world, cfg.worldDifficulty(), cfg.timeLock(), cfg.gamerules(),
                w -> logger.warn("[IG] " + w));

        // ---------- 4. 传送 + 发下注器 + 建记分板 ----------
        for (var pe : getPlayers()) {
            var p = pe.player();
            if (p == null || !p.isOnline()) {
                continue;
            }
            if (cfg.teleportPlayersToSpectate() && arena.spectateSpawn() != null) {
                try {
                    p.teleport(arena.spectateSpawn().toLocation(world));
                } catch (Throwable t) {
                    logger.warn("[IG] 传送 " + p.getName() + " 到观战席失败：" + t);
                }
            }
            plug.menuItemService().give(p);
            plug.scoreboardService().boardFor(p);
            // 终极模式没有资金概念 → 只报生命/护盾（用户第二轮反馈）
            if (cfg.mode().usesFunds()) {
                p.sendMessage(plug.displayConfig().render("start.funds-given",
                        java.util.Map.of("funds", String.valueOf(cfg.initialFunds()))));
            } else {
                p.sendMessage(plug.displayConfig().render("start.stack-given", java.util.Map.of(
                        "life", String.valueOf(cfg.ultimateLife()),
                        "shield", String.valueOf(cfg.ultimateShield()))));
            }
            p.sendMessage(plug.displayConfig().render("start.item-given"));
        }

        // ---------- 5. 开跑 ----------
        RoundManager manager = new RoundManager(plug, players, arena, world,
                seed, cfg.bettingCountdownSeconds());
        // ★ 全部回合打完后必须通知 MGC 收尾。
        //   ⚠️ 必须走 **GameManager**（见 endViaManager 的注释）—— 直接调
        //   AbstractGame.endGame() 只回调我们自己，**不会释放房间**。
        manager.setFinishListener(this::endViaManager);
        plug.setActiveRound(manager);
        manager.start();
        sender().info("<green>第 1 回合生成中…");
    }

    @Override
    protected void onGameCancel() {
        logger.info("[IG] 对局取消（等待阶段未开局）");
    }

    @Override
    protected void onGameAbort() {
        if (skipCleanupWhileDisabling("abort")) {
            return;
        }
        stopSession("abort");
        cleanup("abort");
    }

    @Override
    protected void onGameEnd() {
        if (skipCleanupWhileDisabling("end")) {
            return;
        }
        stopSession("end");
        cleanup("end");
    }

    /**
     * ★ 关服流程里的**安全护栏**（同时是一个判定性实验）。
     *
     * <h2>要查的缺陷</h2>
     * 关服时日志出现：
     * <pre>
     * [MGC: DefaultGameManager] 停止游戏时出错，但仍强制执行停止命令:      ← 消息为空
     * [MCZJUGameCore] 关服时正常停止游戏 infernalguess 失败，将强制清理并继续停止其余游戏：
     *   org.bukkit.plugin.IllegalPluginAccessException:
     *   Plugin attempted to register task while disabled
     * </pre>
     * 已**稳定复现**（{@code /ig debug mgcstart} 造一局真 MGC 对局后直接 {@code stop}）。
     * 但 MGC 只打 {@code e.toString()}，**没有堆栈**，无法直接看出是谁在调度。
     *
     * <h2>这段护栏怎么判定责任方</h2>
     * {@code IllegalPluginAccessException} 是「向**已禁用**的插件注册任务」时抛的。
     * 若异常源于**我们**，那么在 MGC 来停我们对局时，我们的插件必然已经 disabled ——
     * 此时本护栏会命中，并在日志里留下**调用栈**（不再只有一行 toString）。
     * 反过来说：若本护栏**没有**命中而异常照旧出现，就说明调度来自 MGC 自身
     * （它那时确实已经 {@code Disabling}），与我们无关。
     *
     * <p>护栏本身也是正确的行为：插件已禁用后就不该再做收尾（回收道具/记分板、
     * 取消任务都可能触碰调度器）。
     *
     * @return true = 已跳过收尾
     */
    private boolean skipCleanupWhileDisabling(String reason) {
        InfernalGuessPlugin plug = plugin();
        if (plug != null && plug.isEnabled()) {
            return false;
        }
        logger.warn("[IG] 收到对局" + reason + "回调，但本插件已禁用 → 跳过收尾"
                + "（避免在已禁用时注册任务）。调用栈如下，用于判定责任方：");
        try {
            for (StackTraceElement e : new Throwable().getStackTrace()) {
                String cn = e.getClassName();
                if (cn.startsWith("io.mczju")
                        || cn.startsWith("com.github.mczjuops")
                        || cn.startsWith("org.bukkit")
                        || cn.startsWith("io.papermc")) {
                    logger.warn("    at " + e);
                }
            }
        } catch (Throwable ignored) {
            // 打印调用栈失败无所谓
        }
        return true;
    }

    /** 停掉回合状态机并回收玩家身上的东西。 */
    private void stopSession(String reason) {
        var plug = plugin();
        plug.activeRound().ifPresent(r -> r.stop(reason));
        plug.setActiveRound(null);
        for (var pe : getPlayers()) {
            var p = pe.player();
            if (p == null || !p.isOnline()) {
                continue;
            }
            // 1) 回收局内道具与记分板
            try {
                plug.menuItemService().take(p);
                plug.scoreboardService().clear(p);
            } catch (Throwable t) {
                logger.warn("[IG] 回收 " + p.getName() + " 的道具/记分板失败：" + t);
            }
            // 2) ★ 回程：送回房间配的 returnLocation
            //    （未配则回退 wait → 入局前位置 → 主世界 spawn，见 InfernalGuessRoom.teleportReturn）
            //    刻意与上面分开 try：传送失败不该让「回收」看起来也失败了，反之亦然。
            try {
                InfernalGuessRoom.teleportReturn(this, p, joinLocationOf(p.getUniqueId()));
            } catch (Throwable t) {
                logger.warn("[IG] 传送 " + p.getName() + " 回程失败：" + t);
            }
        }
    }

    private void cleanup(String reason) {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        logger.info("[IG] 对局清理（" + reason + "）");
        // ★ 还原对局期间改动过的**世界级**设置（难度 / 游戏规则 / 时间）
        //   与「清场范围」同一个安全思路：子插件不得在世界层面留下永久副作用。
        //   可用 config.yml 的 world.restore-on-end=false 关掉（想让它常驻时）。
        restoreWorldSetup();
        // 局末位置记录只在**本局**有意义，留着会让复用房间的下一局用错兜底位置
        joinLocations.clear();
        // 阶段 6 接入：清场、回收下注器
        // 阶段 7 接入：资金结算与排行
    }

    /**
     * 还原本局改动过的世界级设置（幂等）。
     *
     * <p>只用 {@link WorldSetup#apply} 返回的快照：**没改过的项一律不碰**。
     * 关掉还原（{@code world.restore-on-end: false}）时只打一行日志说明保留了什么，
     * 不做静默行为。
     */
    private void restoreWorldSetup() {
        WorldSetup.Snapshot snap = worldSnapshot;
        worldSnapshot = null;
        if (snap == null || snap.isEmpty()) {
            return;
        }
        if (!plugin().gameConfig().worldRestoreOnEnd()) {
            logger.info("[IG] world.restore-on-end=false → 保留本局写入的世界设置（不还原）");
            return;
        }
        org.bukkit.World w = snap.worldName() == null
                ? null : org.bukkit.Bukkit.getWorld(snap.worldName());
        if (w == null) {
            logger.warn("[IG] 世界设置无法还原：世界 " + snap.worldName() + " 未加载");
            return;
        }
        logger.info("[IG] 还原对局期间改动过的世界设置（难度 / 游戏规则 / 时间）");
        WorldSetup.restore(w, snap, msg -> logger.warn("[IG] " + msg));
    }

    /** 本局是否已清理。 */
    public boolean isCleanedUp() {
        return cleanedUp;
    }

    /**
     * ★★ **结束对局的正确入口**：必须走 {@code GameManager}。
     *
     * <h2>这是「/mgc join 说没有空闲的房间」的**真正根因**（字节码核实）</h2>
     * MGC 1.1.2 里这两个方法**完全不是一回事**：
     * <pre>
     * // AbstractGame.endGame()  —— 我们原先调的就是这个
     * { onGameEnd(); setState(...); }        // 只回调游戏自己，不碰房间、不碰玩家
     *
     * // DefaultGameManager.endGame(game)  —— ★ 正确的正规入口
     * try { game.endGame(); } finally { solveGameEnd(game); }
     *
     * // DefaultGameManager.solveGameEnd(game)
     * game.setState(终态);
     * MCZJUGameCore.getPlayerManager().removeAllPlayer(game);   // 把玩家移出对局
     * game.getGameRoom().setState(GameRoomState.READY);         // ★★ 释放房间
     * gameList.remove(game);
     * </pre>
     *
     * <p>调错后的**实测症状**（与线上日志逐条吻合）：
     * <ul>
     *   <li>我们这边的日志一切正常：{@code 对局结束} → {@code 对局中止（end）}
     *       → {@code 对局清理（end）}</li>
     *   <li>但**房间一直停在 IN_GAME** → 之后 {@code /mgc join} 报
     *       「该游戏没有空闲的房间」</li>
     *   <li>而且**玩家仍留在对局里** —— 日志里要靠玩家自己 {@code /mgc leave}
     *       才把 profile 切回 lobby，正是 {@code removeAllPlayer} 从没跑过的证据</li>
     * </ul>
     * 又因房间 state **不落盘**，重启即恢复 —— 于是表现为「隔一阵子就没房间了」。
     *
     * <p>不会递归：{@code GameManager.endGame} 内部调 {@code game.endGame()} →
     * 我们的 {@code onGameEnd()} 只做停表与回收，**不再回调 endGame**。
     */
    private void endViaManager() {
        try {
            // ★ 必须留这一行：它是「本次结束真的走了正规入口」的**唯一日志证据**。
            //   上一轮排查时正是因为看不到它，才无法判断新 jar 是否生效
            //   （旧实现只打「对局中止/清理」，那两行在两条路径上都会出现）。
            logger.info("[IG] 通知 MGC 结束对局（走 GameManager → 会释放房间）");
            com.github.mczjuops.mczjugamecore.MCZJUGameCore.getGameManager().endGame(this);
        } catch (Throwable t) {
            logger.error("[IG] 通知 MGC 结束对局失败（房间可能被占住，"
                    + "可用 /ig debug room reset 复位）：" + t);
        }
    }

    /**
     * ★ **中止对局的正确入口**（同样走 {@code GameManager}）。
     *
     * <p>理由同 {@link #endViaManager()}：{@code AbstractGame.abortGame()} 只做
     * {@code onGameAbort() + setState()}，**不会释放房间**；
     * {@code GameManager.abortGame(game)} 才会走 {@code solveGameEnd}。
     */
    private void abortViaManager() {
        try {
            logger.info("[IG] 通知 MGC 中止对局（走 GameManager → 会释放房间）");
            com.github.mczjuops.mczjugamecore.MCZJUGameCore.getGameManager().abortGame(this);
        } catch (Throwable t) {
            logger.error("[IG] 通知 MGC 中止对局失败（房间可能被占住）：" + t);
        }
    }

    /**
     * ★ 对局内**一名真人都没有**时立即中止（供 {@link InfernalGuessQuitStrategy} 调用）。
     *
     * <h2>为什么必须由我们收尾</h2>
     * MGC 只在 <b>WAITING</b> 阶段自己处理「人跑光了」——
     * {@code DefaultGameWaitStrategy.onPlayerLeave} 里当 {@code getPlayers().isEmpty()}
     * 会调 {@code GameManager.cancelGame}；而 <b>RUNNING</b> 阶段它只把玩家从自己的名单里
     * 摘掉（{@code DefaultPlayerManager.leaveGame} 的字节码顺序是：
     * 先 {@code playerGameMap.remove} → 再回调 {@code getPlayerQuitStrategy().onPlayerQuit}），
     * **不会**结束对局。于是回合状态机会带着 0 名参与者把当前回合完整打完。
     *
     * <p>实测证据（{@code E:\MCpaper\logs\latest.log}）：
     * <pre>
     *   08:06:02  /mgc leave → [IG] 玩家退出：YamatoMaya（原因 COMMAND_QUIT）
     *   08:06:09  [IG] 第 1 回合开打。下注：（无）        ← 零参与者仍在打
     *   08:06:24  [IG] 已无任何存活参与者，收尾           ← 白空转 22 秒
     * </pre>
     * 这 22 秒里房间一直处于 IN_GAME，玩家再 {@code /mgc join} 会被拒
     * （「该游戏没有空闲的房间」），表现为「无法使用 mgc 大厅工具」。
     *
     * @param trigger 触发原因（写进日志）
     * @return 是否真的发起了中止
     */
    boolean abortIfNoPlayersLeft(String trigger) {
        if (!getPlayers().isEmpty()) {
            return false;   // 还有真人在局内 → 按用户第 4 条继续打
        }
        if (cleanedUp) {
            return false;   // 已经收尾过，避免重复中止
        }
        logger.info("[IG] 对局内已无任何真人（" + trigger + "）→ 立即中止，不再空转整回合");
        abortViaManager();
        return true;
    }

    public InfernalGuessPlugin plugin() {
        return InfernalGuessPlugin.getInstance();
    }

    /** 游戏元信息里的描述行（供其他组件复用）。 */
    public List<String> descriptionLines() {
        return plugin().displayConfig().raw("game.meta-description").lines().toList();
    }
}
