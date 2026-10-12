package io.mczju.infernalguess.battle;

import io.mczju.infernalguess.InfernalGuessPlugin;
import org.bukkit.NamespacedKey;

/**
 * 战斗相关 PDC 键。
 *
 * <p>为什么用 PDC 存**权威阵营**而不是只靠计分板队伍：
 * 队伍是给原版 AI 看的「影子」，而 PDC 是**我们自己的真相来源** ——
 * 即使队伍被清理、实体被变形（炒鸡的 {@code morph} 词条），PDC 仍随实体存在。
 */
public final class BattleTags {

    private BattleTags() {
    }

    /** 阵营：{@code "left"} / {@code "right"}。 */
    public static NamespacedKey side() {
        return new NamespacedKey(InfernalGuessPlugin.getInstance(), "side");
    }

    /**
     * 是否**计数单位**（{@code "1"} / {@code "0"}）。
     *
     * <p>计数单位计入「一方全灭」判定；非计数单位（如唤魔者召唤的恼鬼）参战但不计数。
     */
    public static NamespacedKey counted() {
        return new NamespacedKey(InfernalGuessPlugin.getInstance(), "counted");
    }

    /** 所属战斗实例 id（防止跨回合误清）。 */
    public static NamespacedKey battleId() {
        return new NamespacedKey(InfernalGuessPlugin.getInstance(), "battle");
    }

    /** 该实体携带的炒鸡词条 id 列表（逗号分隔，仅用于调试展示）。 */
    public static NamespacedKey affixes() {
        return new NamespacedKey(InfernalGuessPlugin.getInstance(), "affixes");
    }
}
