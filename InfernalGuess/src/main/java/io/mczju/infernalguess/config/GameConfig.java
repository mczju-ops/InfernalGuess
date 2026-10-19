package io.mczju.infernalguess.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code config.yml} 的类型化视图。
 *
 * <p>全部字段在 {@link #load} 中**一次性校验**，任何缺失/越界/长度不符都会抛出
 * {@link ConfigError} 并指出具体键路径。加载成功后本对象不可变。
 */
public final class GameConfig {

    /** 文件名，用于错误信息。 */
    public static final String FILE = "config.yml";

    // ---------- 对局 ----------
    private final long initialFunds;
    private final int maxPlayers;
    private final int minPlayers;
    private final int rounds;
    private final int bettingCountdownSeconds;
    private final double[] betRatios;
    private final Rounding rounding;

    // ---------- 数值表 ----------
    private final int[] weightByRound;
    private final long[] killLineByRound;
    private final double weightTolerance;
    private final Rounding weightRounding;

    // ---------- 词条（2026-10-11 起为**自有**体系，不再依赖炒鸡 API）----------
    private final boolean affixesEnabled;
    private final int[] affixCountByRound;

    // ---------- 模式 ----------
    private final GameMode mode;
    private final int ultimateLife;
    private final int ultimateShield;
    private final int ultimateShieldUntilRound;
    private final boolean ultimateOvertime;
    private final boolean botEnabled;
    private final int botCount;
    private final List<String> botNames;

    // ---------- 竞技场与世界 ----------
    private final String arenaWorld;
    private final boolean teleportPlayersToSpectate;
    private final String worldDifficulty;
    private final String timeLock;
    private final String[] worldTimeLockValues;
    private final Map<String, Boolean> gamerules;
    /**
     * 对局结束时是否**还原**本插件改动过的世界级设置（难度 / 游戏规则 / 时间）。
     *
     * <p>★ 2026-10-13 新增：竞技场常常就建在**主世界**（现场实测），
     * 「只写不还」等于在主世界留下永久副作用。默认 {@code true}：
     * 想让它常驻（例如希望 {@code mobGriefing: false} 一直生效）就设成 {@code false}。
     */
    private final boolean worldRestoreOnEnd;
    /**
     * 美西螈「假死复活」：致命伤害时改为假死，N 秒后带血复活（2026-10-13 用户要求）。
     *
     * <p>它解决的是「美西螈**打不出伤害**」这个死结（原版只攻击白名单目标）——
     * 让它成为一个**能拖时间/扛伤害**的单位，而不是一个站着不动的活靶。
     */
    private final boolean axolotlReviveEnabled;
    /** 每只美西螈能复活几次（0 = 关）。**必须有上限**，否则全场打不完。 */
    private final int axolotlReviveCount;
    /** 假死持续秒数。 */
    private final int axolotlReviveSeconds;
    /** 复活后血量（占最大生命的百分比）。 */
    private final int axolotlReviveHealthPercent;

    // ---------- 战斗 ----------
    private final double followRange;
    private final double mobScale;

    /**
     * ★ 按生物类型**额外**放大的倍率（{@code battle.mob-attributes.scale-overrides}）。
     *
     * <p>键是**原版生物 id**（如 {@code silverfish} / {@code endermite} / {@code fox}），
     * 值是倍率。用途只有一个：把「包围盒太小 ⇒ 远处会被客户端剔除」的单位放大，
     * 让观众在竞技场任何位置都看得见（公式与依据见
     * {@link io.mczju.infernalguess.battle.RenderCulling}）。
     *
     * <p>⚠️ 这里**刻意不查 {@code Registry.ENTITY_TYPE}**：本类要能在**离线单测**里加载
     * （{@code ShippedConfigTest} 会读出厂 config.yml），而注册表只有服务端有。
     * id 是否合法由 {@code InfernalGuessPlugin} 在启动时用注册表核对并告警。
     */
    private final Map<String, Double> scaleOverrides;

    private final boolean clearRandomEquipment;
    private final boolean preventItemPickup;
    private final int cleanupDelayTicks;
    private final boolean protectMenuItem;
    private final int maxFightSeconds;
    private final boolean poisonFogEnabled;
    private final int poisonFogSeconds;
    private final int poisonFogAmplifier;

    // ---------- 调试 ----------
    private final boolean debugEnabled;

    private GameConfig(Builder b) {
        this.initialFunds = b.initialFunds;
        this.maxPlayers = b.maxPlayers;
        this.minPlayers = b.minPlayers;
        this.rounds = b.rounds;
        this.bettingCountdownSeconds = b.bettingCountdownSeconds;
        this.betRatios = b.betRatios;
        this.rounding = b.rounding;
        this.weightByRound = b.weightByRound;
        this.killLineByRound = b.killLineByRound;
        this.weightTolerance = b.weightTolerance;
        this.weightRounding = b.weightRounding;
        this.affixesEnabled = b.affixesEnabled;
        this.affixCountByRound = b.affixCountByRound;
        this.mode = b.mode;
        this.ultimateLife = b.ultimateLife;
        this.ultimateShield = b.ultimateShield;
        this.ultimateShieldUntilRound = b.ultimateShieldUntilRound;
        this.ultimateOvertime = b.ultimateOvertime;
        this.botEnabled = b.botEnabled;
        this.botCount = b.botCount;
        this.botNames = b.botNames;
        this.arenaWorld = b.arenaWorld;
        this.teleportPlayersToSpectate = b.teleportPlayersToSpectate;
        this.worldDifficulty = b.worldDifficulty;
        this.timeLock = b.timeLock;
        this.worldTimeLockValues = b.worldTimeLockValues;
        this.gamerules = b.gamerules;
        this.worldRestoreOnEnd = b.worldRestoreOnEnd;
        this.axolotlReviveEnabled = b.axolotlReviveEnabled;
        this.axolotlReviveCount = b.axolotlReviveCount;
        this.axolotlReviveSeconds = b.axolotlReviveSeconds;
        this.axolotlReviveHealthPercent = b.axolotlReviveHealthPercent;
        this.followRange = b.followRange;
        this.mobScale = b.mobScale;
        this.scaleOverrides = b.scaleOverrides;
        this.clearRandomEquipment = b.clearRandomEquipment;
        this.preventItemPickup = b.preventItemPickup;
        this.cleanupDelayTicks = b.cleanupDelayTicks;
        this.protectMenuItem = b.protectMenuItem;
        this.maxFightSeconds = b.maxFightSeconds;
        this.poisonFogEnabled = b.poisonFogEnabled;
        this.poisonFogSeconds = b.poisonFogSeconds;
        this.poisonFogAmplifier = b.poisonFogAmplifier;
        this.debugEnabled = b.debugEnabled;
    }

    // ================= 加载与校验 =================

    public static GameConfig load(FileConfiguration cfg) {
        Builder b = new Builder();

        // ---- 对局 ----
        b.maxPlayers = ConfigReader.requireInt(cfg, FILE, "max-players", 1, 100);
        b.minPlayers = ConfigReader.requireInt(cfg, FILE, "min-players", 1, 100);
        if (b.minPlayers > b.maxPlayers) {
            throw new ConfigError(FILE, "min-players",
                    "不能大于 max-players（" + b.minPlayers + " > " + b.maxPlayers + "）");
        }
        b.rounds = ConfigReader.requireInt(cfg, FILE, "rounds", 1, 100);
        b.initialFunds = ConfigReader.requireLong(cfg, FILE, "initial-funds");
        if (b.initialFunds <= 0) {
            throw new ConfigError(FILE, "initial-funds", "必须为正数，实际 " + b.initialFunds);
        }
        b.bettingCountdownSeconds = ConfigReader.requireInt(cfg, FILE, "betting-countdown-seconds", 1, 3600);

        List<Double> ratios = ConfigReader.requireDoubleArrayAsList(cfg, FILE, "bet-ratios");
        if (ratios.isEmpty()) {
            throw new ConfigError(FILE, "bet-ratios", "不能为空");
        }
        for (int i = 0; i < ratios.size(); i++) {
            double r = ratios.get(i);
            if (r <= 0.0 || r > 1.0) {
                throw new ConfigError(FILE, "bet-ratios[" + i + "]", "应在 (0, 1] 区间，实际 " + r);
            }
        }
        b.betRatios = ratios.stream().mapToDouble(Double::doubleValue).toArray();
        b.rounding = ConfigReader.requireEnum(cfg, FILE, "rounding", Rounding.class, Rounding::valueOf);

        // ---- 数值表（长度必须 = 回合数）----
        b.weightByRound = ConfigReader.requireIntArray(cfg, FILE, "weight-by-round", b.rounds);
        b.killLineByRound = ConfigReader.requireLongArray(cfg, FILE, "kill-line-by-round", b.rounds);
        for (int i = 0; i < b.weightByRound.length; i++) {
            if (b.weightByRound[i] < 0) {
                throw new ConfigError(FILE, "weight-by-round[" + i + "]", "不能为负数");
            }
        }
        for (int i = 0; i < b.killLineByRound.length; i++) {
            if (b.killLineByRound[i] < 0) {
                throw new ConfigError(FILE, "kill-line-by-round[" + i + "]", "不能为负数");
            }
        }
        b.weightTolerance = ConfigReader.requireDouble(cfg, FILE, "weight-tolerance", 0.0, 1.0);
        b.weightRounding = ConfigReader.requireEnum(cfg, FILE, "weight-rounding", Rounding.class, Rounding::valueOf);

        // ---- 词条（自有体系；2026-10-11 起不再依赖炒鸡 API）----
        b.affixesEnabled = ConfigReader.requireBoolean(cfg, FILE, "enable-affixes");
        b.affixCountByRound = ConfigReader.requireIntArray(cfg, FILE, "affixes.count-by-round", b.rounds);
        for (int i = 0; i < b.affixCountByRound.length; i++) {
            int v = b.affixCountByRound[i];
            if (v < 0 || v > 10) {
                throw new ConfigError(FILE, "affixes.count-by-round[" + i + "]",
                        "应在 [0, 10]，实际 " + v);
            }
        }

        // ---- 模式 ----
        b.mode = ConfigReader.requireEnum(cfg, FILE, "mode", GameMode.class, GameMode::valueOf);
        b.ultimateLife = ConfigReader.requireInt(cfg, FILE, "ultimate.life", 1, 100);
        b.ultimateShield = ConfigReader.requireInt(cfg, FILE, "ultimate.shield", 0, 100);
        b.ultimateShieldUntilRound = ConfigReader.requireInt(cfg, FILE, "ultimate.shield-until-round", 1, b.rounds);
        b.ultimateOvertime = ConfigReader.requireBoolean(cfg, FILE, "ultimate.overtime");
        b.botEnabled = ConfigReader.requireBoolean(cfg, FILE, "bot.enabled");
        b.botCount = ConfigReader.requireInt(cfg, FILE, "bot.bots", 0, 100);
        b.botNames = ConfigReader.requireStringList(cfg, FILE, "bot.names");

        // ---- 竞技场与世界 ----
        b.arenaWorld = ConfigReader.requireString(cfg, FILE, "arena.world");
        b.teleportPlayersToSpectate = ConfigReader.requireBoolean(cfg, FILE, "arena.teleport-players-to-spectate");
        b.worldDifficulty = ConfigReader.requireString(cfg, FILE, "world.difficulty");
        b.timeLock = ConfigReader.requireString(cfg, FILE, "world.time-lock");
        b.worldTimeLockValues = new String[]{"day", "noon", "night", "midnight", "none"};
        boolean lockOk = false;
        for (String s : b.worldTimeLockValues) {
            if (s.equalsIgnoreCase(b.timeLock)) {
                lockOk = true;
                break;
            }
        }
        if (!lockOk) {
            throw new ConfigError(FILE, "world.time-lock",
                    "非法取值 `" + b.timeLock + "`，合法取值：" + String.join(" | ", b.worldTimeLockValues));
        }
        Map<String, Boolean> rules = new LinkedHashMap<>();
        var gr = ConfigReader.requireSection(cfg, FILE, "world.gamerules");
        for (String key : gr.getKeys(false)) {
            Object v = gr.get(key);
            if (!(v instanceof Boolean bool)) {
                throw new ConfigError(FILE, "world.gamerules." + key, "应为 true/false");
            }
            rules.put(key, bool);
        }
        b.worldRestoreOnEnd = ConfigReader.requireBoolean(cfg, FILE, "world.restore-on-end");
        b.axolotlReviveEnabled = ConfigReader.requireBoolean(cfg, FILE, "axolotl-revive.enabled");
        b.axolotlReviveCount = ConfigReader.requireInt(cfg, FILE, "axolotl-revive.count", 0, 10);
        b.axolotlReviveSeconds = ConfigReader.requireInt(cfg, FILE, "axolotl-revive.seconds", 1, 60);
        b.axolotlReviveHealthPercent =
                ConfigReader.requireInt(cfg, FILE, "axolotl-revive.health-percent", 1, 100);
        b.gamerules = Map.copyOf(rules);

        // ---- 战斗 ----
        b.followRange = ConfigReader.requireDouble(cfg, FILE, "battle.follow-range", 1.0, 256.0);
        b.mobScale = ConfigReader.requireDouble(cfg, FILE, "battle.mob-attributes.scale", 0.05, 10.0);
        b.scaleOverrides = readScaleOverrides(cfg);
        b.clearRandomEquipment = ConfigReader.requireBoolean(cfg, FILE, "battle.mob-attributes.clear-random-equipment");
        b.preventItemPickup = ConfigReader.requireBoolean(cfg, FILE, "battle.mob-attributes.prevent-item-pickup");
        b.cleanupDelayTicks = ConfigReader.requireInt(cfg, FILE, "battle.cleanup-delay-ticks", 0, 200);
        b.protectMenuItem = ConfigReader.requireBoolean(cfg, FILE, "battle.protect-menu-item");
        b.maxFightSeconds = ConfigReader.requireInt(cfg, FILE, "battle.max-fight-seconds", 5, 3600);
        b.poisonFogEnabled = ConfigReader.requireBoolean(cfg, FILE, "battle.poison-fog-enabled");
        b.poisonFogSeconds = ConfigReader.requireInt(cfg, FILE, "battle.poison-fog-seconds", 1, 3600);
        b.poisonFogAmplifier = ConfigReader.requireInt(cfg, FILE, "battle.poison-fog-amplifier", 0, 10);

        // ---- 调试 ----
        b.debugEnabled = ConfigReader.requireBoolean(cfg, FILE, "debug.enabled");

        return new GameConfig(b);
    }

    /**
     * 读 {@code battle.mob-attributes.scale-overrides}（生物 id → 额外倍率）。
     *
     * <p>只校验**数值范围**（原版 {@code minecraft:scale} 属性的定义域 {@code [0.0625, 16.0]}）；
     * id 是否真的存在交给启动时的注册表核对（原因见字段注释）。
     */
    private static Map<String, Double> readScaleOverrides(FileConfiguration cfg) {
        ConfigurationSection sec =
                cfg.getConfigurationSection("battle.mob-attributes.scale-overrides");
        if (sec == null) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : sec.getKeys(false)) {
            String path = "battle.mob-attributes.scale-overrides." + key;
            double value = sec.getDouble(key, 1.0);
            if (!(value >= 0.0625 && value <= 16.0)) {
                throw new ConfigError(FILE, path,
                        "倍率必须在 0.0625~16.0 之间（原版 scale 属性范围），实际 " + value);
            }
            out.put(key.toLowerCase(Locale.ROOT), value);
        }
        return Map.copyOf(out);
    }

    // ================= 访问器 =================

    public long initialFunds() { return initialFunds; }
    public int maxPlayers() { return maxPlayers; }
    public int minPlayers() { return minPlayers; }
    public int rounds() { return rounds; }
    public int bettingCountdownSeconds() { return bettingCountdownSeconds; }
    public double[] betRatios() { return betRatios.clone(); }
    public Rounding rounding() { return rounding; }

    /** 第 {@code round} 回合（1-based）的目标总权重。 */
    public int weightForRound(int round) {
        return weightByRound[checkRound(round)];
    }

    /** 第 {@code round} 回合（1-based）的斩杀线。 */
    public long killLineForRound(int round) {
        return killLineByRound[checkRound(round)];
    }

    /**
     * 第 {@code round} 回合（1-based）一组**最多**能命中的词条数（Q46）。
     * 返回 {@code 0} 表示该回合**完全不生成词条**（Q49），即不走炒鸡流程。
     *
     * <p>⚠️ **{@code enable-infernal-api: false} 时恒为 0**，原因是一个必须避免的语义不一致：
     * 规划器按 {@code 单怪权重 × 数量 × Π(1 + 词条加成)} 算组权重，
     * 但炒鸡关掉时**根本不会生成带词条的怪** —— 于是权重表反映的战力与实际不符。
     *
     * <p>实测踩到：默认配置正是「API 关闭 + 词条表非零」，
     * 日志打出 {@code 苦力怕×2 [berserk] → 权重 14.80}，但生成的是普通苦力怕。
     *
     * <p>把开关与词条表绑定后，「权重」这个数才真正等于实际战力。
     */
    public int affixCountForRound(int round) {
        if (!affixesEnabled()) {   // 走访问器，好让调试覆盖生效
            return 0;
        }
        return affixCountByRound[checkRound(round)];
    }

    /** 原始词条表（不受 {@code enable-affixes} 影响）—— 供启动日志与测试查看配置意图。 */
    public int rawAffixCountForRound(int round) {
        return affixCountByRound[checkRound(round)];
    }

    /** 词条表是否非零但词条系统不可用（此时应提示运维）—— 用于启动期告警。 */
    public boolean hasAffixTableButAffixesUnavailable() {
        if (affixesEnabled()) {
            return false;
        }
        for (int v : affixCountByRound) {
            if (v > 0) {
                return true;
            }
        }
        return false;
    }

    public double weightTolerance() { return weightTolerance; }
    public Rounding weightRounding() { return weightRounding; }

    /** 词条系统总开关（可被调试覆盖）。默认 false —— 自研词条系统接入前不生成词条。 */
    public boolean affixesEnabled() {
        return affixesOverride != null ? affixesOverride : affixesEnabled;
    }

    /** 本局模式（可被调试覆盖）。 */
    public GameMode mode() {
        return modeOverride != null ? modeOverride : mode;
    }
    public int ultimateLife() { return ultimateLife; }
    public int ultimateShield() { return ultimateShield; }
    public int ultimateShieldUntilRound() { return ultimateShieldUntilRound; }
    public boolean ultimateOvertime() { return ultimateOvertime; }
    /** 是否加入人机（可被调试覆盖）。 */
    public boolean botEnabled() {
        return botsEnabledOverride != null ? botsEnabledOverride : botEnabled;
    }

    /** 人机数量（可被调试覆盖）。 */
    public int botCount() {
        return botCountOverride != null ? botCountOverride : botCount;
    }

    public List<String> botNames() { return botNames; }

    public String arenaWorld() { return arenaWorld; }
    public boolean teleportPlayersToSpectate() { return teleportPlayersToSpectate; }
    public String worldDifficulty() { return worldDifficulty; }
    public String timeLock() { return timeLock; }
    public Map<String, Boolean> gamerules() { return gamerules; }
    public boolean worldRestoreOnEnd() { return worldRestoreOnEnd; }

    // ---------- 美西螈「假死复活」（2026-10-13 用户要求）----------
    public boolean axolotlReviveEnabled() { return axolotlReviveEnabled; }
    public int axolotlReviveCount() { return axolotlReviveCount; }
    public int axolotlReviveSeconds() { return axolotlReviveSeconds; }
    public int axolotlReviveHealthPercent() { return axolotlReviveHealthPercent; }

    public double followRange() { return followRange; }
    public double mobScale() { return mobScale; }

    /**
     * ★ 该生物类型在本局里的**最终体型倍率** = 全局 {@code scale} × 该类型的
     * {@code scale-overrides}（再夹到原版 {@code scale} 属性的值域）。
     *
     * <p>装配时应用到 {@code Attribute.SCALE}。用途：把包围盒太小的单位放大，
     * 免得它们在竞技场那头被**客户端**剔除（见
     * {@link io.mczju.infernalguess.battle.RenderCulling}）。
     */
    public double scaleFor(String entityTypeId) {
        double value = mobScale * scaleOverrides.getOrDefault(entityTypeId.toLowerCase(Locale.ROOT), 1.0);
        return Math.max(0.0625, Math.min(16.0, value));
    }

    /** 见 {@link #scaleFor(String)}；服务端侧按 {@link EntityType} 取原版 id 再查。 */
    public double scaleFor(EntityType type) {
        return scaleFor(type.getKey().getKey());
    }

    /** 按类型配置的放大倍率表（只读，供启动核对与测试）。 */
    public Map<String, Double> scaleOverrides() { return scaleOverrides; }
    public boolean clearRandomEquipment() { return clearRandomEquipment; }
    public boolean preventItemPickup() { return preventItemPickup; }
    public int cleanupDelayTicks() { return cleanupDelayTicks; }
    public boolean protectMenuItem() { return protectMenuItem; }

    /** 单回合战斗的兜底超时（秒）：到点仍未分出胜负就按存活数判定。 */
    public int maxFightSeconds() { return maxFightSeconds; }

    /** 是否启用毒雾（开战后 N 秒给场上所有生物挂永久凋零）。 */
    public boolean poisonFogEnabled() { return poisonFogEnabled; }

    /** 开战后多少秒起雾。 */
    public int poisonFogSeconds() { return poisonFogSeconds; }

    /** 毒雾的凋零等级（0 = 凋零 I）。 */
    public int poisonFogAmplifier() { return poisonFogAmplifier; }

    // ================= 运行时覆盖（仅供 /ig debug 指令使用）=================
    //
    // 目的：让调试者**不用改 config.yml 重启**就能切「资金/终极模式、人机开关、炒鸡开关」。
    // 约定：null = 用配置文件的值。取值一律走下面的访问器，不要直接读字段。

    private GameMode modeOverride;
    private Boolean botsEnabledOverride;
    private Integer botCountOverride;
    private Boolean affixesOverride;

    /** 设置模式覆盖；传 {@code null} 恢复用配置文件的值。 */
    public void setModeOverride(GameMode m) {
        this.modeOverride = m;
    }

    /** 设置人机覆盖；{@code enabled == null} 恢复配置值。 */
    public void setBotsOverride(Boolean enabled, Integer count) {
        this.botsEnabledOverride = enabled;
        this.botCountOverride = count;
    }

    /** 设置炒鸡覆盖；传 {@code null} 恢复用配置文件的值。 */
    public void setAffixesOverride(Boolean b) {
        this.affixesOverride = b;
    }

    /** 清空全部运行时覆盖。 */
    public void clearOverrides() {
        this.modeOverride = null;
        this.botsEnabledOverride = null;
        this.botCountOverride = null;
        this.affixesOverride = null;
    }

    /** 当前生效设置的人类可读描述（调试指令用）。 */
    public String describeEffective() {
        return "模式 " + mode()
                + " ｜ 人机 " + (botEnabled() ? ("开（" + botCount() + " 个）") : "关")
                + " ｜ 词条 " + (affixesEnabled() ? "开" : "关")
                + " ｜ 覆盖中的项：" + describeOverrides();
    }

    private String describeOverrides() {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (modeOverride != null) {
            list.add("mode=" + modeOverride);
        }
        if (botsEnabledOverride != null) {
            list.add("bots=" + botsEnabledOverride + "/" + botCountOverride);
        }
        if (affixesOverride != null) {
            list.add("affixes=" + affixesOverride);
        }
        return list.isEmpty() ? "（无）" : String.join(", ", list);
    }

    /** 是否允许普通玩家使用 {@code /ig debug}。 */
    public boolean debugEnabled() { return debugEnabled; }

    /** 终极模式在第 {@code round} 回合开始时是否已无护盾。 */
    public boolean shieldGoneAtRound(int round) {
        return round >= ultimateShieldUntilRound;
    }

    /** 护盾生效到第几回合（含）。第 7 回合起失效 —— 用于记分板提示。 */
    public int shieldUntilRound() {
        return ultimateShieldUntilRound;
    }

    private int checkRound(int round) {
        if (round < 1 || round > rounds) {
            throw new IllegalArgumentException("回合数超出范围： " + round + "（应为 1.." + rounds + "）");
        }
        return round - 1;
    }

    // ================= Builder（仅内部使用） =================

    private static final class Builder {
        long initialFunds;
        int maxPlayers, minPlayers, rounds, bettingCountdownSeconds;
        double[] betRatios;
        Rounding rounding;
        int[] weightByRound;
        long[] killLineByRound;
        double weightTolerance;
        Rounding weightRounding;
        boolean affixesEnabled;
        int[] affixCountByRound;
        GameMode mode;
        int ultimateLife, ultimateShield, ultimateShieldUntilRound;
        boolean ultimateOvertime, botEnabled;
        int botCount;
        List<String> botNames;
        String arenaWorld;
        boolean teleportPlayersToSpectate;
        String worldDifficulty, timeLock;
        String[] worldTimeLockValues;
        Map<String, Boolean> gamerules;
        boolean worldRestoreOnEnd;
        boolean axolotlReviveEnabled;
        int axolotlReviveCount, axolotlReviveSeconds, axolotlReviveHealthPercent;
        double followRange, mobScale;
        Map<String, Double> scaleOverrides = Map.of();
        boolean clearRandomEquipment, preventItemPickup;
        int cleanupDelayTicks;
        boolean protectMenuItem;
        int maxFightSeconds;
        boolean poisonFogEnabled;
        int poisonFogSeconds;
        int poisonFogAmplifier;
        boolean debugEnabled;
    }
}
