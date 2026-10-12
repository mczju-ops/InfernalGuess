package io.mczju.infernalguess.config;

import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fox;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ★ **变种应用器**：把 {@code mob-groups.yml} 里 {@code variant:} 段的属性写到实体上。
 *
 * <h2>为什么需要它</h2>
 * 有些形态**没有自己的原版实体 id**，因此无法用「组键 = 实体 id」表达：
 * <ul>
 *   <li><b>闪电苦力怕</b> —— 还是 {@code CREEPER}，只是 {@code setPowered(true)}</li>
 *   <li><b>白狐狸</b> —— 还是 {@code FOX}，只是毛色 {@code SNOW}</li>
 *   <li><b>美西螈颜色</b> —— 还是 {@code AXOLOTL}，只是 {@code Variant}</li>
 * </ul>
 * 用户明确点名要「闪电苦力怕」与「叼着钻石剑的白色狐狸变种」，
 * 所以这一类必须支持。
 *
 * <h2>★ 设计：schema 稳定 + 未识别键**必须告警**</h2>
 * 配置侧只做「字符串 → 字符串」的透传（见 {@code MobGroupConfig.parseVariant}），
 * 键名白名单在**本类**。这样加一个新变种只需改这一处。
 *
 * <p>但透传的代价是「写错一个键名不会报错」—— 那正是本项目反复踩过的
 * 「配了却静默无效」的坑。因此 {@link #apply} 对**未识别的键**返回告警列表，
 * 由调用方打进日志；加载期还会用 {@link #validate} 直接拒绝启动。
 *
 * <h2>键名一览（全小写下划线）</h2>
 * <table>
 *   <tr><td>{@code charged}</td><td>苦力怕充能（true/false）→ 闪电苦力怕</td></tr>
 *   <tr><td>{@code fox_type}</td><td>狐狸毛色：{@code RED} | {@code SNOW}</td></tr>
 *   <tr><td>{@code axolotl_variant}</td><td>美西螈颜色：{@code LUCY} | {@code WILD} |
 *       {@code GOLD} | {@code CYAN} | {@code BLUE}</td></tr>
 * </table>
 */
public final class MobVariant {

    /** 全部受支持的变种键。 */
    public static final Set<String> KNOWN_KEYS = Set.of("charged", "fox_type", "axolotl_variant");

    private MobVariant() {
    }

    /**
     * 校验一个组的变种声明是否**合法且与该实体类型匹配**。
     *
     * <p>为什么要在**加载期**做（而不是等生成时告警）：用户唯一能看到的反馈就是启动日志，
     * 而「变种没生效」在游戏里只表现为「这只苦力怕怎么没闪电」——
     * 极难归因。宁可拒绝启动并指出键名。
     *
     * @return 问题列表（空 = 合法）
     */
    public static java.util.List<String> validate(String path, Map<String, String> variant,
                                                  Class<?> entityClass) {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (variant == null || variant.isEmpty()) {
            return problems;
        }
        for (Map.Entry<String, String> e : variant.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            String value = e.getValue();
            if (!KNOWN_KEYS.contains(key)) {
                problems.add(path + "." + e.getKey() + " 不是已支持的变种属性（受支持："
                        + new java.util.TreeSet<>(KNOWN_KEYS) + "）");
                continue;
            }
            switch (key) {
                case "charged" -> {
                    if (entityClass == null || !Creeper.class.isAssignableFrom(entityClass)) {
                        problems.add(path + ".charged 只能用于苦力怕，实际实体是 "
                                + (entityClass == null ? "?" : entityClass.getSimpleName()));
                    }
                    if (!isBoolean(value)) {
                        problems.add(path + ".charged 应为 true/false，实际 `" + value + "`");
                    }
                }
                case "fox_type" -> {
                    if (entityClass == null || !Fox.class.isAssignableFrom(entityClass)) {
                        problems.add(path + ".fox_type 只能用于狐狸，实际实体是 "
                                + (entityClass == null ? "?" : entityClass.getSimpleName()));
                    }
                    if (value == null || !value.equalsIgnoreCase("RED")
                            && !value.equalsIgnoreCase("SNOW")) {
                        problems.add(path + ".fox_type 应为 RED | SNOW，实际 `" + value + "`");
                    }
                }
                case "axolotl_variant" -> {
                    if (entityClass == null || !Axolotl.class.isAssignableFrom(entityClass)) {
                        problems.add(path + ".axolotl_variant 只能用于美西螈，实际实体是 "
                                + (entityClass == null ? "?" : entityClass.getSimpleName()));
                    }
                    if (value == null || parseAxolotl(value) == null) {
                        problems.add(path + ".axolotl_variant 取值非法，实际 `" + value + "`");
                    }
                }
                default -> {
                    // KNOWN_KEYS 已拦住，走不到这里
                }
            }
        }
        return problems;
    }

    /**
     * 把变种属性**实际写到实体上**。
     *
     * @return 未能应用的键（空 = 全部成功）；调用方应把它打进日志
     */
    public static java.util.List<String> apply(Entity entity, Map<String, String> variant) {
        java.util.List<String> failed = new java.util.ArrayList<>();
        if (entity == null || variant == null || variant.isEmpty()) {
            return failed;
        }
        for (Map.Entry<String, String> e : variant.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            String value = e.getValue();
            boolean ok = switch (key) {
                case "charged" -> {
                    if (entity instanceof Creeper creeper) {
                        creeper.setPowered(isBoolean(value) && Boolean.parseBoolean(value));
                        yield true;
                    }
                    yield false;
                }
                case "fox_type" -> {
                    if (entity instanceof Fox fox && value != null) {
                        if (value.equalsIgnoreCase("SNOW")) {
                            fox.setFoxType(Fox.Type.SNOW);
                            yield true;
                        }
                        if (value.equalsIgnoreCase("RED")) {
                            fox.setFoxType(Fox.Type.RED);
                            yield true;
                        }
                    }
                    yield false;
                }
                case "axolotl_variant" -> {
                    Axolotl.Variant v = parseAxolotl(value);
                    if (entity instanceof Axolotl axolotl && v != null) {
                        axolotl.setVariant(v);
                        yield true;
                    }
                    yield false;
                }
                default -> false;
            };
            if (!ok) {
                failed.add(key + "=" + value);
            }
        }
        return failed;
    }

    /** 把 {@code variant} 段折成一行，便于写进启动日志与剧本描述。 */
    public static String describe(Map<String, String> variant) {
        if (variant == null || variant.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new LinkedHashMap<>(variant).entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static boolean isBoolean(String s) {
        return s != null && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"));
    }

    /** 解析美西螈颜色；非法返回 {@code null}。 */
    private static Axolotl.Variant parseAxolotl(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "LUCY" -> Axolotl.Variant.LUCY;
            case "WILD" -> Axolotl.Variant.WILD;
            case "GOLD" -> Axolotl.Variant.GOLD;
            case "CYAN" -> Axolotl.Variant.CYAN;
            case "BLUE" -> Axolotl.Variant.BLUE;
            default -> null;
        };
    }
}
