package io.mczju.infernalguess.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 带校验的配置读取工具。
 *
 * <p>与 Bukkit 原生 {@code getInt(path, def)} 的区别：**缺失即报错**，
 * 并明确指出是哪个文件的哪个键，避免「静默取默认值」导致的问题被掩盖。
 */
public final class ConfigReader {

    private ConfigReader() {
    }

    // ---------------- 基础读取 ----------------

    public static Object require(FileConfiguration cfg, String file, String path) {
        if (!cfg.isSet(path)) {
            throw new ConfigError(file, path, "缺失（必须配置）");
        }
        return cfg.get(path);
    }

    public static int requireInt(FileConfiguration cfg, String file, String path) {
        Object v = require(cfg, file, path);
        if (v instanceof Number n) {
            return n.intValue();
        }
        throw new ConfigError(file, path, "应为整数，实际是 " + typeName(v));
    }

    public static int requireInt(FileConfiguration cfg, String file, String path, int min, int max) {
        int v = requireInt(cfg, file, path);
        if (v < min || v > max) {
            throw new ConfigError(file, path, "超出允许范围 [" + min + ", " + max + "]，实际 " + v);
        }
        return v;
    }

    public static long requireLong(FileConfiguration cfg, String file, String path) {
        Object v = require(cfg, file, path);
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new ConfigError(file, path, "应为整数，实际是 " + typeName(v));
    }

    public static double requireDouble(FileConfiguration cfg, String file, String path) {
        Object v = require(cfg, file, path);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new ConfigError(file, path, "应为数字，实际是 " + typeName(v));
    }

    public static double requireDouble(FileConfiguration cfg, String file, String path, double min, double max) {
        double v = requireDouble(cfg, file, path);
        if (v < min || v > max) {
            throw new ConfigError(file, path, "超出允许范围 [" + min + ", " + max + "]，实际 " + v);
        }
        return v;
    }

    public static boolean requireBoolean(FileConfiguration cfg, String file, String path) {
        Object v = require(cfg, file, path);
        if (v instanceof Boolean b) {
            return b;
        }
        throw new ConfigError(file, path, "应为 true/false，实际是 " + typeName(v));
    }

    public static String requireString(FileConfiguration cfg, String file, String path) {
        Object v = require(cfg, file, path);
        if (v instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new ConfigError(file, path, "应为非空字符串，实际是 " + typeName(v));
    }

    public static List<String> requireStringList(FileConfiguration cfg, String file, String path) {
        Object v = require(cfg, file, path);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (!(o instanceof String s)) {
                    throw new ConfigError(file, path, "列表元素应为字符串，实际是 " + typeName(o));
                }
                out.add(s);
            }
            return List.copyOf(out);
        }
        throw new ConfigError(file, path, "应为字符串列表，实际是 " + typeName(v));
    }

    public static ConfigurationSection requireSection(FileConfiguration cfg, String file, String path) {
        ConfigurationSection s = cfg.getConfigurationSection(path);
        if (s == null) {
            throw new ConfigError(file, path, "缺失或不是配置段");
        }
        return s;
    }

    // ---------------- 数组（长度 = 回合数，逐项校验） ----------------

    /**
     * 读取「索引 = 回合数 - 1」的整数数组，并校验长度。
     *
     * @param expectedSize 期望长度（= 回合数）
     */
    public static int[] requireIntArray(FileConfiguration cfg, String file, String path, int expectedSize) {
        List<Integer> list = cfg.getIntegerList(path);
        if (list.isEmpty() && !cfg.isSet(path)) {
            throw new ConfigError(file, path, "缺失（必须配置）");
        }
        if (list.size() != expectedSize) {
            throw new ConfigError(file, path,
                    "长度应为 " + expectedSize + "（= 回合数），实际 " + list.size());
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    /** 读取「索引 = 回合数 - 1」的长整数数组，并校验长度。 */
    public static long[] requireLongArray(FileConfiguration cfg, String file, String path, int expectedSize) {
        Object raw = require(cfg, file, path);
        if (!(raw instanceof List<?> list)) {
            throw new ConfigError(file, path, "应为列表，实际是 " + typeName(raw));
        }
        if (list.size() != expectedSize) {
            throw new ConfigError(file, path,
                    "长度应为 " + expectedSize + "（= 回合数），实际 " + list.size());
        }
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) {
            Object o = list.get(i);
            if (!(o instanceof Number n)) {
                throw new ConfigError(file, path + "[" + i + "]", "应为数字，实际是 " + typeName(o));
            }
            out[i] = n.longValue();
        }
        return out;
    }

    /** 读取「索引 = 回合数 - 1」的小数数组，并校验长度。 */
    public static double[] requireDoubleArray(FileConfiguration cfg, String file, String path, int expectedSize) {
        Object raw = require(cfg, file, path);
        if (!(raw instanceof List<?> list)) {
            throw new ConfigError(file, path, "应为列表，实际是 " + typeName(raw));
        }
        if (list.size() != expectedSize) {
            throw new ConfigError(file, path,
                    "长度应为 " + expectedSize + "，实际 " + list.size());
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            Object o = list.get(i);
            if (!(o instanceof Number n)) {
                throw new ConfigError(file, path + "[" + i + "]", "应为数字，实际是 " + typeName(o));
            }
            out[i] = n.doubleValue();
        }
        return out;
    }

    /** 读取小数列表（不校验长度，调用方自行校验）。 */
    public static List<Double> requireDoubleArrayAsList(FileConfiguration cfg, String file, String path) {
        Object raw = require(cfg, file, path);
        if (!(raw instanceof List<?> list)) {
            throw new ConfigError(file, path, "应为列表，实际是 " + typeName(raw));
        }
        List<Double> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (!(o instanceof Number n)) {
                throw new ConfigError(file, path + "[" + i + "]", "应为数字，实际是 " + typeName(o));
            }
            out.add(n.doubleValue());
        }
        return List.copyOf(out);
    }

    // ---------------- 枚举 ----------------
    /**
     * 读取枚举名（**大小写不敏感**），非法值立即报错并列出合法取值。
     */
    public static <E extends Enum<E>> E requireEnum(FileConfiguration cfg, String file, String path,
                                                    Class<E> type, Function<String, E> parser) {
        String raw = requireString(cfg, file, path);
        E v = parser.apply(raw.trim().toUpperCase(java.util.Locale.ROOT));
        if (v == null) {
            throw new ConfigError(file, path, "非法取值 `" + raw + "`，合法取值："
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
        return v;
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }
}
