package io.mczju.infernalguess.config;

import io.mczju.infernalguess.InfernalGuessPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code arenas.yml} 的加载、校验与保存。
 *
 * <p>用户第 6 条：竞技场在**固定的 {@code lobby} 世界**里、已由玩家修建完成，
 * 插件**不生成新世界**，只记录坐标用于传送玩家与生成生物。
 * 本文件由 {@code /ig arena ...} 调试指令与调试 UI 维护，**不需要手工编辑**。
 *
 * <p>坐标**直接存绝对坐标**（Q42）——Maggoteers 的「层原点 + 相对坐标」是为了让同一张图
 * 复制到多个层原点复用，我们没有那个需求。
 *
 * <p>注意：**本文件允许为空**（新服还没建竞技场），此时只记录警告；
 * 真正开局时会再检查是否有「完整」的竞技场。
 */
public final class ArenaConfig {

    public static final String FILE = "arenas.yml";

    /** 每侧固定 3 个刷怪点。 */
    public static final int POINTS_PER_SIDE = 3;

    private final Map<String, Arena> arenas;

    /** 一个三维点。 */
    public record Point(double x, double y, double z) {
        public Location toLocation(World world) {
            return new Location(world, x, y, z);
        }
    }

    /** 落点（含朝向）。 */
    public record Spawn(double x, double y, double z, float yaw, float pitch) {
        public Location toLocation(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }
    }

    /** 一个竞技场。 */
    public record Arena(String name,
                        String world,
                        Spawn playerSpawn,
                        Spawn spectateSpawn,
                        Map<Integer, Point> leftPoints,
                        Map<Integer, Point> rightPoints) {
        public Arena {
            leftPoints = Map.copyOf(leftPoints);
            rightPoints = Map.copyOf(rightPoints);
        }

        /** 是否可用于开局：世界存在 + 有玩家落点 + 左右各 3 个刷怪点。 */
        public boolean isComplete() {
            return world != null && !world.isBlank()
                    && playerSpawn != null
                    && leftPoints.size() == POINTS_PER_SIDE
                    && rightPoints.size() == POINTS_PER_SIDE;
        }

        /** 人类可读的残缺说明（完整时返回空串）。 */
        public String incompleteness() {
            List<String> missing = new ArrayList<>();
            if (world == null || world.isBlank()) {
                missing.add("world");
            }
            if (playerSpawn == null) {
                missing.add("player-spawn");
            }
            if (leftPoints.size() != POINTS_PER_SIDE) {
                missing.add("left-points(" + leftPoints.size() + "/" + POINTS_PER_SIDE + ")");
            }
            if (rightPoints.size() != POINTS_PER_SIDE) {
                missing.add("right-points(" + rightPoints.size() + "/" + POINTS_PER_SIDE + ")");
            }
            return String.join(", ", missing);
        }
    }

    private ArenaConfig(Map<String, Arena> arenas) {
        this.arenas = new LinkedHashMap<>(arenas);
    }

    /** 测试用工厂：直接构造，不读文件。 */
    public static ArenaConfig of(java.util.Map<String, Arena> arenas) {
        return new ArenaConfig(arenas);
    }

    // ================= 加载 =================

    public static ArenaConfig load(InfernalGuessPlugin plugin) {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.isFile()) {
            plugin.saveResource(FILE, false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        Map<String, Arena> out = new LinkedHashMap<>();
        ConfigurationSection root = cfg.getConfigurationSection("arenas");
        if (root != null) {
            for (String name : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(name);
                if (s == null) {
                    throw new ConfigError(FILE, "arenas." + name, "应为配置段");
                }
                Arena arena = parseArena(name, s);
                out.put(name.toLowerCase(Locale.ROOT), arena);
                if (!arena.isComplete()) {
                    plugin.getLogger().warning(FILE + "：竞技场 `" + name
                            + "` 尚不完整（缺少 " + arena.incompleteness() + "），开局时不可用。");
                }
            }
        }

        if (out.isEmpty()) {
            plugin.getLogger().warning(FILE + "：还没有任何竞技场。"
                    + "请用 /ig arena create <名称> 与调试 UI 绑定传送点与刷怪点。");
        } else {
            long complete = out.values().stream().filter(Arena::isComplete).count();
            if (complete == 0) {
                plugin.getLogger().warning(FILE + "：共 " + out.size()
                        + " 个竞技场，但没有一个是完整的 → 当前无法开局。");
            }
        }

        return new ArenaConfig(out);
    }

    private static Arena parseArena(String name, ConfigurationSection s) {
        String path = "arenas." + name;
        String world = s.getString("world");

        Spawn player = parseSpawn(s.getConfigurationSection("player-spawn"), FILE, path + ".player-spawn");
        Spawn spectate = parseSpawn(s.getConfigurationSection("spectate-spawn"), FILE, path + ".spectate-spawn");

        Map<Integer, Point> left = parsePoints(s.getConfigurationSection("left-points"), FILE, path + ".left-points");
        Map<Integer, Point> right = parsePoints(s.getConfigurationSection("right-points"), FILE, path + ".right-points");

        return new Arena(name, world, player, spectate, left, right);
    }

    private static Spawn parseSpawn(ConfigurationSection s, String file, String path) {
        if (s == null) {
            return null;
        }
        return new Spawn(
                requireNum(s, file, path + ".x", "x"),
                requireNum(s, file, path + ".y", "y"),
                requireNum(s, file, path + ".z", "z"),
                (float) s.getDouble("yaw", 0.0),
                (float) s.getDouble("pitch", 0.0));
    }

    private static Map<Integer, Point> parsePoints(ConfigurationSection s, String file, String path) {
        Map<Integer, Point> out = new LinkedHashMap<>();
        if (s == null) {
            return out;
        }
        for (String key : s.getKeys(false)) {
            int idx;
            try {
                idx = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                throw new ConfigError(file, path + "." + key, "点位号应为整数（1~" + POINTS_PER_SIDE + "）");
            }
            if (idx < 1 || idx > POINTS_PER_SIDE) {
                throw new ConfigError(file, path + "." + key,
                        "点位号应在 1~" + POINTS_PER_SIDE + "，实际 " + idx);
            }
            ConfigurationSection p = s.getConfigurationSection(key);
            if (p == null) {
                throw new ConfigError(file, path + "." + key, "应为含 x/y/z 的配置段");
            }
            out.put(idx, new Point(
                    requireNum(p, file, path + "." + key + ".x", "x"),
                    requireNum(p, file, path + "." + key + ".y", "y"),
                    requireNum(p, file, path + "." + key + ".z", "z")));
        }
        return out;
    }

    private static double requireNum(ConfigurationSection s, String file, String fullPath, String key) {
        Object v = s.get(key);
        if (!(v instanceof Number n)) {
            throw new ConfigError(file, fullPath, "缺失或不是数字");
        }
        return n.doubleValue();
    }

    // ================= 保存（供调试指令调用） =================

    /** 把当前内存模型写回 {@code arenas.yml}。 */
    public void save(InfernalGuessPlugin plugin) throws IOException {
        File file = new File(plugin.getDataFolder(), FILE);
        FileConfiguration cfg = new YamlConfiguration();
        for (Arena a : arenas.values()) {
            String base = "arenas." + a.name();
            cfg.set(base + ".world", a.world());
            if (a.playerSpawn() != null) {
                writeSpawn(cfg, base + ".player-spawn", a.playerSpawn());
            }
            if (a.spectateSpawn() != null) {
                writeSpawn(cfg, base + ".spectate-spawn", a.spectateSpawn());
            }
            a.leftPoints().forEach((i, p) -> writePoint(cfg, base + ".left-points." + i, p));
            a.rightPoints().forEach((i, p) -> writePoint(cfg, base + ".right-points." + i, p));
        }
        cfg.save(file);
    }

    private static void writeSpawn(FileConfiguration cfg, String path, Spawn s) {
        cfg.set(path + ".x", s.x());
        cfg.set(path + ".y", s.y());
        cfg.set(path + ".z", s.z());
        cfg.set(path + ".yaw", (double) s.yaw());
        cfg.set(path + ".pitch", (double) s.pitch());
    }

    private static void writePoint(FileConfiguration cfg, String path, Point p) {
        cfg.set(path + ".x", p.x());
        cfg.set(path + ".y", p.y());
        cfg.set(path + ".z", p.z());
    }

    // ================= 变更（供调试指令调用） =================

    /** 新建竞技场（同名已存在则返回空）。 */
    public Optional<Arena> create(String name, String world) {
        String key = name.toLowerCase(Locale.ROOT);
        if (arenas.containsKey(key)) {
            return Optional.empty();
        }
        Arena a = new Arena(name, world, null, null, Map.of(), Map.of());
        arenas.put(key, a);
        return Optional.of(a);
    }

    public boolean delete(String name) {
        return arenas.remove(name.toLowerCase(Locale.ROOT)) != null;
    }

    public boolean rename(String oldName, String newName) {
        Arena a = arenas.remove(oldName.toLowerCase(Locale.ROOT));
        if (a == null || arenas.containsKey(newName.toLowerCase(Locale.ROOT))) {
            if (a != null) {
                arenas.put(oldName.toLowerCase(Locale.ROOT), a); // 回滚
            }
            return false;
        }
        arenas.put(newName.toLowerCase(Locale.ROOT), new Arena(newName, a.world(),
                a.playerSpawn(), a.spectateSpawn(), a.leftPoints(), a.rightPoints()));
        return true;
    }

    public boolean setPlayerSpawn(String name, Spawn spawn) {
        return replace(name, a -> new Arena(a.name(), a.world(), spawn,
                a.spectateSpawn(), a.leftPoints(), a.rightPoints()));
    }

    public boolean setSpectateSpawn(String name, Spawn spawn) {
        return replace(name, a -> new Arena(a.name(), a.world(), a.playerSpawn(),
                spawn, a.leftPoints(), a.rightPoints()));
    }

    public boolean setPoint(String name, boolean leftSide, int index, Point point) {
        if (index < 1 || index > POINTS_PER_SIDE) {
            return false;
        }
        return replace(name, a -> {
            Map<Integer, Point> map = new LinkedHashMap<>(leftSide ? a.leftPoints() : a.rightPoints());
            map.put(index, point);
            return leftSide
                    ? new Arena(a.name(), a.world(), a.playerSpawn(), a.spectateSpawn(), map, a.rightPoints())
                    : new Arena(a.name(), a.world(), a.playerSpawn(), a.spectateSpawn(), a.leftPoints(), map);
        });
    }

    private boolean replace(String name, java.util.function.UnaryOperator<Arena> fn) {
        String key = name.toLowerCase(Locale.ROOT);
        Arena a = arenas.get(key);
        if (a == null) {
            return false;
        }
        arenas.put(key, fn.apply(a));
        return true;
    }

    // ================= 访问 =================

    public Collection<Arena> arenas() {
        return List.copyOf(arenas.values());
    }

    public Optional<Arena> get(String name) {
        return Optional.ofNullable(arenas.get(name.toLowerCase(Locale.ROOT)));
    }

    /** 可用于开局的竞技场（完整者）。 */
    public List<Arena> playable() {
        return arenas.values().stream().filter(Arena::isComplete).toList();
    }

    public boolean isEmpty() {
        return arenas.isEmpty();
    }

    /** 解析某个竞技场的世界对象（世界不存在时返回空）。 */
    public static Optional<World> resolveWorld(Arena arena) {
        World w = Bukkit.getWorld(arena.world());
        return Optional.ofNullable(w);
    }
}
