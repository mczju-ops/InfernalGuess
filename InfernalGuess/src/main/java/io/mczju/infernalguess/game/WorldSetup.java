package io.mczju.infernalguess.game;

import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;

import java.util.Locale;
import java.util.Map;

/**
 * 对局开始时把竞技场世界调成需要的状态。
 *
 * <p>对应 {@code config.yml} 的 {@code world.difficulty} 与 {@code world.time-lock}。
 * 全部失败都只警告、不中断开局（世界设置不是玩法必需的）。
 */
public final class WorldSetup {

    private WorldSetup() {
    }

    public static void apply(World world, String difficulty, String timeLock,
                             Map<String, Boolean> gamerules,
                             java.util.function.Consumer<String> warn) {
        if (world == null) {
            return;
        }
        // 难度
        if (difficulty != null && !difficulty.isBlank()
                && !difficulty.equalsIgnoreCase("none")) {
            try {
                world.setDifficulty(Difficulty.valueOf(difficulty.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                warn.accept("未知难度 " + difficulty + "，已跳过。");
            }
        }
        // 游戏规则
        // ⚠️ 这些键在 GameConfig 里是**严格校验**的，如果这里不写入就是「配置声明了却不生效」的静默空转。
        if (gamerules != null) {
            for (Map.Entry<String, Boolean> e : gamerules.entrySet()) {
                GameRule<?> rule = resolveRule(e.getKey());
                if (rule == null) {
                    warn.accept("未知游戏规则 `" + e.getKey() + "`，已跳过（检查 world.gamerules）。");
                    continue;
                }
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
        // 锁时间
        if (timeLock == null || timeLock.isBlank() || timeLock.equalsIgnoreCase("none")) {
            return;
        }
        try {
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

    /** 兼容旧调用（不带 gamerules）。 */
    public static void apply(World world, String difficulty, String timeLock,
                             java.util.function.Consumer<String> warn) {
        apply(world, difficulty, timeLock, Map.of(), warn);
    }

    /**
     * ★ **经典 gamerule 名 → 26.2 注册表键**（**实测得来**，不是照版本印象猜的）。
     *
     * <h2>为什么需要这张表</h2>
     * 26.2 把一批经典 gamerule **改了注册表键**，而配置与 {@code /gamerule} 命令里
     * 大家写的仍是**经典名** → 直接按 snake_case 查注册表会**查不到**。
     *
     * <p>实测（在测试服的 `Registry.GAME_RULE` 上把 59 个键全打出来对出来的）：
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
