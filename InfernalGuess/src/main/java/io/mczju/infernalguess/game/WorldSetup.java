package io.mczju.infernalguess.game;

import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 对局开始时把竞技场世界调成需要的状态，**对局结束时把改动还原**。
 *
 * <p>对应 {@code config.yml} 的 {@code world.difficulty} / {@code world.time-lock} /
 * {@code world.gamerules} / {@code world.restore-on-end}。
 * 全部失败都只警告、不中断开局（世界设置不是玩法必需的）。
 *
 * <h2>★ 为什么必须能还原（2026-10-13 安全审查的结论）</h2>
 * 这些设置是**世界级**的，而现场 {@code arenas.yml} 里竞技场 {@code test1} 就建在
 * <b>主世界</b>（{@code world: world}）——「只写不还」等于子插件在主世界留下永久副作用：
 * 难度被改、{@code mobGriefing} 被关、时间被锁死。按
 * {@code skills/mgc-subplugin-safety} 的规则，广域状态修改必须
 * <b>记录原值 + 可选还原</b>，因此：
 * <ul>
 *   <li>{@link #apply} 返回 {@link Snapshot}（只记录**真的被改动**的项）</li>
 *   <li>对局结束时由 {@code InfernalGuessGame} 调 {@link #restore}</li>
 *   <li>{@code world.restore-on-end: false} 可关掉还原（想让它常驻时用）</li>
 * </ul>
 */
public final class WorldSetup {

    private WorldSetup() {
    }

    /**
     * 应用前的世界状态快照 —— **只记录真的被本插件改动的项**。
     *
     * @param worldName              被改动的世界名（还原时用它取回 World）
     * @param previousDifficulty     原难度；{@code null} = 没改难度
     * @param previousGamerules      被改动的规则键 → 原值（键是注册表键，如 {@code mob_griefing}）
     * @param previousTime           原世界时间；{@code null} = 没改时间
     * @param previousDaylightCycle  原「昼夜更替」开关；{@code null} = 没改
     */
    public record Snapshot(String worldName,
                           Difficulty previousDifficulty,
                           Map<String, Boolean> previousGamerules,
                           Long previousTime,
                           Boolean previousDaylightCycle) {

        public Snapshot {
            previousGamerules = Collections.unmodifiableMap(new LinkedHashMap<>(previousGamerules));
        }

        /** 什么都没改（例如世界为 null）时的空快照。 */
        public static final Snapshot EMPTY = new Snapshot(null, null, Map.of(), null, null);

        /** 是否有需要还原的项。 */
        public boolean isEmpty() {
            return previousDifficulty == null && previousGamerules.isEmpty()
                    && previousTime == null && previousDaylightCycle == null;
        }
    }

    /** {@link #planGamerules} 的结果：{@code apply} = 要写入的，{@code previous} = 要还原的原值。 */
    record GamerulePlan(Map<String, Boolean> apply, Map<String, Boolean> previous) {
    }

    /**
     * 纯逻辑：算出「要写入的规则」与「需要还原的原值」。
     *
     * <p>规则：**只有真的会被改动**（当前值与目标值不同）的键才进 {@code previous} ——
     * 值本来就一样的键不该在还原时被"写回去"，否则等于替别人做了一次无谓的写入
     * （读不到的键也不记：宁可少还原，也不写错值）。
     *
     * @param configured 已解析成注册表键的目标值（如 {@code mob_griefing → false}）
     * @param current    这些键**当前的实际值**（读不到的键直接不在 map 里）
     */
    static GamerulePlan planGamerules(Map<String, Boolean> configured, Map<String, Boolean> current) {
        Map<String, Boolean> apply = new LinkedHashMap<>(configured);
        Map<String, Boolean> previous = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> e : configured.entrySet()) {
            Boolean now = current.get(e.getKey());
            if (now != null && !now.equals(e.getValue())) {
                previous.put(e.getKey(), now);
            }
        }
        return new GamerulePlan(apply, previous);
    }

    /**
     * 把世界调成需要的状态，并返回**用于还原的快照**。
     *
     * @return 快照（世界为 {@code null} 时返回 {@link Snapshot#EMPTY}）；
     *         调用方应在对局结束时交给 {@link #restore}
     */
    public static Snapshot apply(World world, String difficulty, String timeLock,
                                 Map<String, Boolean> gamerules,
                                 java.util.function.Consumer<String> warn) {
        if (world == null) {
            return Snapshot.EMPTY;
        }
        String worldName = world.getName();

        // ---------- 难度（先记原值） ----------
        Difficulty previousDifficulty = null;
        if (difficulty != null && !difficulty.isBlank()
                && !difficulty.equalsIgnoreCase("none")) {
            try {
                Difficulty target = Difficulty.valueOf(difficulty.toUpperCase(Locale.ROOT));
                previousDifficulty = world.getDifficulty();
                world.setDifficulty(target);
            } catch (IllegalArgumentException e) {
                warn.accept("未知难度 " + difficulty + "，已跳过。");
            }
        }

        // ---------- 游戏规则（只记录**真的被改动**的那些） ----------
        // ⚠️ 这些键在 GameConfig 里是**严格校验**的，如果这里不写入就是「配置声明了却不生效」的静默空转。
        Map<String, Boolean> previousRules = new LinkedHashMap<>();
        if (gamerules != null && !gamerules.isEmpty()) {
            Map<String, Boolean> configuredResolved = new LinkedHashMap<>();
            Map<String, GameRule<?>> ruleByKey = new LinkedHashMap<>();
            for (Map.Entry<String, Boolean> e : gamerules.entrySet()) {
                GameRule<?> rule = resolveRule(e.getKey());
                if (rule == null) {
                    warn.accept("未知游戏规则 `" + e.getKey() + "`，已跳过（检查 world.gamerules）。");
                    continue;
                }
                String key = rule.getKey().toString();
                configuredResolved.put(key, e.getValue());
                ruleByKey.put(key, rule);
            }
            Map<String, Boolean> current = new LinkedHashMap<>();
            for (Map.Entry<String, GameRule<?>> e : ruleByKey.entrySet()) {
                Boolean now = readBooleanRule(world, e.getValue());
                if (now != null) {
                    current.put(e.getKey(), now);
                }
            }
            GamerulePlan plan = planGamerules(configuredResolved, current);
            previousRules.putAll(plan.previous());
            for (Map.Entry<String, Boolean> e : plan.apply().entrySet()) {
                GameRule<?> rule = ruleByKey.get(e.getKey());
                try {
                    @SuppressWarnings("unchecked")
                    GameRule<Object> typed = (GameRule<Object>) rule;
                    if (!world.setGameRule(typed, (Object) e.getValue())) {
                        warn.accept("设置游戏规则 `" + e.getKey() + "` 失败，已跳过。");
                    }
                } catch (Throwable t) {
                    warn.accept("设置游戏规则 `" + e.getKey() + "` 出错：" + t);
                }
            }
        }

        // ---------- 锁时间（先记原值与原开关） ----------
        Long previousTime = null;
        Boolean previousDaylightCycle = null;
        if (timeLock != null && !timeLock.isBlank() && !timeLock.equalsIgnoreCase("none")) {
            try {
                previousTime = world.getTime();
                previousDaylightCycle = readBooleanRule(world, GameRule.DO_DAYLIGHT_CYCLE);
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                world.setTime(switch (timeLock.toLowerCase(Locale.ROOT)) {
                    case "day" -> 1000L;
                    case "noon" -> 6000L;
                    case "night" -> 13000L;
                    case "midnight" -> 18000L;
                    default -> {
                        warn.accept("未知时间锁 " + timeLock + "，已跳过。");
                        yield world.getTime();
                    }
                });
            } catch (Throwable t) {
                warn.accept("设置时间锁失败：" + t);
            }
        }

        Snapshot snapshot = new Snapshot(worldName, previousDifficulty, previousRules,
                previousTime, previousDaylightCycle);
        return snapshot;
    }

    /**
     * 还原 {@link #apply} 改动过的**世界级**设置（对局结束时调用，幂等）。
     *
     * <p>只用快照里记录的项：没改过的项一律不碰。
     */
    public static void restore(World world, Snapshot snapshot,
                               java.util.function.Consumer<String> warn) {
        if (world == null || snapshot == null || snapshot.isEmpty()) {
            return;
        }
        if (snapshot.previousDifficulty() != null) {
            try {
                world.setDifficulty(snapshot.previousDifficulty());
            } catch (Throwable t) {
                warn.accept("还原难度失败：" + t);
            }
        }
        for (Map.Entry<String, Boolean> e : snapshot.previousGamerules().entrySet()) {
            GameRule<?> rule = resolveRule(e.getKey());
            if (rule == null) {
                warn.accept("还原游戏规则 `" + e.getKey() + "` 失败：规则名无法解析");
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                GameRule<Object> typed = (GameRule<Object>) rule;
                world.setGameRule(typed, (Object) e.getValue());
            } catch (Throwable t) {
                warn.accept("还原游戏规则 `" + e.getKey() + "` 出错：" + t);
            }
        }
        if (snapshot.previousDaylightCycle() != null) {
            try {
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, snapshot.previousDaylightCycle());
            } catch (Throwable t) {
                warn.accept("还原昼夜更替开关失败：" + t);
            }
        }
        if (snapshot.previousTime() != null) {
            try {
                world.setTime(snapshot.previousTime());
            } catch (Throwable t) {
                warn.accept("还原世界时间失败：" + t);
            }
        }
    }

    /** 读一条布尔型游戏规则；读不到（注册表/世界不支持）返回 {@code null}。 */
    private static Boolean readBooleanRule(World world, GameRule<?> rule) {
        if (world == null || rule == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            GameRule<Object> typed = (GameRule<Object>) (GameRule<?>) rule;
            Object v = world.getGameRuleValue(typed);
            return v instanceof Boolean b ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 兼容旧调用（不带 gamerules）。 */
    public static Snapshot apply(World world, String difficulty, String timeLock,
                                 java.util.function.Consumer<String> warn) {
        return apply(world, difficulty, timeLock, Map.of(), warn);
    }

    /**
     * ★ **经典 gamerule 名 → 26.2 注册表键**（**实测得来**，不是照版本印象猜的）。
     *
     * <h2>为什么需要这张表</h2>
     * 26.2 把一批经典 gamerule **改了注册表键**，而配置与 {@code /gamerule} 命令里
     * 大家写的仍是**经典名** → 直接按 snake_case 查注册表会**查不到**。
     *
     * <p>实测（在测试服的 {@code Registry.GAME_RULE} 上把 59 个键全打出来对出来的）：
     * <pre>
     *   doMobSpawning        → spawn_mobs
     *   doMobLoot            → mob_drops
     *   naturalRegeneration  → natural_health_regeneration
     *   doDaylightCycle      → advance_time
     *   doWeatherCycle       → advance_weather
     *   doTileDrops          → block_drops
     *   doEntityDrops        → entity_drops
     *   …（其余见下表）
     *   mobGriefing / keepInventory 等**没改名**，直接用 snake_case 即可
     * </pre>
     * 这正是之前「5 条规则里 3 条报未知」的真正原因 ——
     * 光把 camelCase 转成 snake_case 是不够的。
     */
    private static final Map<String, String> CLASSIC_ALIASES = Map.ofEntries(
            Map.entry("domobspawning", "spawn_mobs"),
            Map.entry("domobloot", "mob_drops"),
            Map.entry("naturalregeneration", "natural_health_regeneration"),
            Map.entry("dotiledrops", "block_drops"),
            Map.entry("doentitydrops", "entity_drops"),
            Map.entry("dodaylightcycle", "advance_time"),
            Map.entry("doweathercycle", "advance_weather"),
            Map.entry("doimmediaterespawn", "immediate_respawn"),
            Map.entry("doinsomnia", "spawn_phantoms"),
            Map.entry("dopatrolspawning", "spawn_patrols"),
            Map.entry("dotraderspawning", "spawn_wandering_traders"),
            Map.entry("dowardenspawning", "spawn_wardens"),
            Map.entry("domonsterspawning", "spawn_monsters"),
            Map.entry("dolimitedcrafting", "limited_crafting"),
            Map.entry("disableelytramovementcheck", "elytra_movement_check"),
            Map.entry("announceadvancements", "show_advancement_messages"),
            Map.entry("maxcommandchainlength", "max_command_sequence_length"),
            Map.entry("maxcommandforkcount", "max_command_forks"),
            Map.entry("maxblockmodifications", "max_block_modifications"),
            Map.entry("spawnradius", "respawn_radius"),
            Map.entry("randomtickspeed", "random_tick_speed"),
            Map.entry("maxentitycramming", "max_entity_cramming"),
            Map.entry("playerssleepingpercentage", "players_sleeping_percentage"),
            Map.entry("reduceddebuginfo", "reduced_debug_info"),
            Map.entry("spectatorsgeneratechunks", "spectators_generate_chunks"),
            Map.entry("snowaccumulationheight", "max_snow_accumulation_height"),
            Map.entry("globalsoundevents", "global_sound_events")
    );

    /**
     * 把配置里的游戏规则名解析成 {@link GameRule}。
     *
     * <h2>★ 为什么不能用 {@code world.setGameRuleValue(String, String)}（实测缺陷）</h2>
     * 26.2 上它对本项目配置里的 5 条规则 ——
     * {@code doMobSpawning} / {@code mobGriefing} / {@code naturalRegeneration} /
     * {@code keepInventory} / {@code doMobLoot} —— **一律返回 {@code false}**，
     * 于是日志刷「未知游戏规则 … 已跳过」，**配置声明了却全部不生效**（用户线上日志实测）。
     *
     * <h2>解析顺序</h2>
     * <ol>
     *   <li>归一成 snake_case（{@code doMobSpawning} → {@code do_mob_spawning}）</li>
     *   <li>若它在 {@link #CLASSIC_ALIASES} 里（即 26.2 改过名）→ 换成新键</li>
     *   <li>按 {@link org.bukkit.Registry#GAME_RULE} 查</li>
     *   <li>查不到再用原始 snake_case 名义试一次（覆盖「用户直接写新名」的情形）</li>
     * </ol>
     *
     * @return 解析到的规则；无法解析时返回 {@code null}
     */
    public static GameRule<?> resolveRule(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String name = rawName.trim();
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);   // 去掉 minecraft: 之类的前缀
        }
        String snake = toSnakeCase(name);
        // 经典名（去掉下划线后）→ 26.2 的新键
        String alias = CLASSIC_ALIASES.get(snake.replace("_", ""));
        GameRule<?> rule = lookup(alias != null ? alias : snake);
        if (rule == null) {
            rule = lookup(snake);   // 用户直接写了新名（如 spawn_mobs）
        }
        return rule;
    }

    private static GameRule<?> lookup(String key) {
        try {
            return org.bukkit.Registry.GAME_RULE.get(
                    org.bukkit.NamespacedKey.minecraft(key));
        } catch (Throwable t) {
            return null;   // 注册表不可用（如纯单元测试环境）→ 当未知处理
        }
    }

    /**
     * camelCase / kebab-case → snake_case。
     *
     * <p>原版注册表用 snake_case（{@code do_mob_spawning}），
     * 而配置与 {@code /gamerule} 命令用 camelCase（{@code doMobSpawning}）。
     */
    static String toSnakeCase(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replace('-', '_').replace(' ', '_');
        if (t.isEmpty()) {
            return "";
        }
        boolean hasUpper = false;
        boolean hasLower = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isUpperCase(c)) {
                hasUpper = true;
            } else if (Character.isLowerCase(c)) {
                hasLower = true;
            }
        }
        // ① 全大写（枚举常量风格，如 DO_MOB_SPAWNING）或已全小写 → 只做小写归一。
        //    ⚠️ 这一条是**单元测试抓出来的真实缺陷**：早期实现只做「大写前插下划线」，
        //    于是 DO_MOB_SPAWNING 会变成 d_o__m_o_b__s_p_a_w_n_i_n_g ——
        //    而用户写成枚举常量风格是完全可能的。
        if (hasUpper && !hasLower) {
            return t.toLowerCase(Locale.ROOT);
        }
        // ② camelCase → 每个大写字母前补下划线
        StringBuilder b = new StringBuilder(t.length() + 4);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isUpperCase(c)) {
                if (b.length() > 0 && b.charAt(b.length() - 1) != '_') {
                    b.append('_');
                }
                b.append(Character.toLowerCase(c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
