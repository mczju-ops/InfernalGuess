package io.mczju.infernalguess.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.game.GameMeta;
import com.github.mczjuops.mczjugamecore.game.strategy.wait.DefaultGameWaitStrategy;
import com.github.mczjuops.mczjugamecore.game.strategy.wait.GameWaitStrategy;
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

    @Override
    public GameWaitStrategy getGameWaitStrategy() {
        GameConfig g = plugin().gameConfig();
        return new DefaultGameWaitStrategy(this, g.maxPlayers(), g.minPlayers());
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

        // ---------- 1+3. 选竞技场 + 组参与者（纯逻辑，见 SessionPlanner，有单元测试覆盖）----------
        // 本局种子先生成，因为它同时决定「随机挑哪个竞技场」与整个剧本
        long seed = new java.util.Random().nextLong();
        String roomArenaName = getGameRoom() instanceof InfernalGuessRoom room
                ? room.arenaName : null;
        var realPlayers = new java.util.ArrayList<SessionPlanner.Participant>();
        for (var pe : getPlayers()) {
            realPlayers.add(new SessionPlanner.Participant(
                    pe.getUniqueId(), pe.getName(), false));
        }
        SessionPlanner.SessionPlan plan = SessionPlanner.plan(
                arenas, cfg, roomArenaName, realPlayers, seed);
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
        WorldSetup.apply(world, cfg.worldDifficulty(), cfg.timeLock(), cfg.gamerules(),
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
            try {
                plug.menuItemService().take(p);
                plug.scoreboardService().clear(p);
            } catch (Throwable t) {
                logger.warn("[IG] 回收 " + p.getName() + " 的道具/记分板失败：" + t);
            }
        }
    }

    private void cleanup(String reason) {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        logger.info("[IG] 对局清理（" + reason + "）");
        // 阶段 6 接入：清场、回收下注器
        // 阶段 7 接入：资金结算与排行
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

    public InfernalGuessPlugin plugin() {
        return InfernalGuessPlugin.getInstance();
    }

    /** 游戏元信息里的描述行（供其他组件复用）。 */
    public List<String> descriptionLines() {
        return plugin().displayConfig().raw("game.meta-description").lines().toList();
    }
}
