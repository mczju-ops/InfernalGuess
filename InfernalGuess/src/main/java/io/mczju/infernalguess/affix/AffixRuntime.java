package io.mczju.infernalguess.affix;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * **一个实体身上的词条运行时状态**（对标炒鸡的 {@code MobState} 中与状态有关的那部分）。
 *
 * <h2>为什么必须有它</h2>
 * 逐行读炒鸡实现后发现：技能类本身往往只是个声明，真正的「持续效果」靠一份挂在实体上的状态：
 * <ul>
 *   <li>{@code PassiveLifestealSkill} 挨打时只写一个带到期 tick 的 buff
 *       （{@code lifesteal_until}），**治疗是 service 在 tick 里做的**</li>
 *   <li>{@code 1up} 靠 {@code hasUsedOneTime / useOneTimeIfNotUsed} 保证**只触发一次**</li>
 *   <li>各词条靠 {@code isOnCooldown / setCooldown} 限流</li>
 * </ul>
 * 所以自研系统必须有等价能力，否则「只触发一次」「持续时间」「冷却」都无处安放。
 *
 * <h2>★ 三条设计要求（都来自实测教训）</h2>
 * <ol>
 *   <li><b>一次性标记必须真的只有一次</b> —— {@code 1up} 若每 tick 都能触发就会
 *       **永不死亡的怪 → 对局无法结束**</li>
 *   <li><b>冷却用的是绝对 tick</b>（{@code Bukkit.getCurrentTick()} 语义）——
 *       炒鸡用自己的自增计数器，{@code reload} 后归零会让已有冷却立刻失效</li>
 *   <li><b>清场时整体丢弃</b>（由 {@code AffixRegistry#clearRuntime} 统一做）——
 *       否则跨回合残留会让下一回合的怪带着上一局的状态</li>
 * </ol>
 */
public final class AffixRuntime {

    /** 已经用掉的一次性词条 id。 */
    private final Set<String> usedOnce = new HashSet<>();

    /** 词条 id → 冷却结束的绝对 tick。 */
    private final Map<String, Long> cooldowns = new HashMap<>();

    /** 词条 id → buff 到期的绝对 tick（键里带上提供者 id，避免不同词条互相覆盖）。 */
    private final Map<String, Long> buffs = new HashMap<>();

    /** 当前装配在该实体身上的词条 id（按装配顺序，供日志与调试）。 */
    private final Set<String> equipped = new LinkedHashSet<>();

    /**
     * 计数器：词条 id/键 → 一个长整数。
     *
     * <p>用途：**「本轮已经做了几次」**这类需要累计的量，例如
     * {@code lifesteal} 本次窗口已经回了几次血、{@code mama}/{@code vexsummoner}
     * 已经召唤了几只。
     *
     * <p>★ 与 buff 分开存是刻意的：buff 会被 {@link #prune} 按时间回收，
     * 而计数器是**按语义重置**的（例如新一轮窗口开始时清零），
     * 放一起会被误清（数值小的时候会被当成「已过期的 tick」）。
     */
    private final Map<String, Long> counters = new HashMap<>();

    // ---------------- 装配记录 ----------------

    public void markEquipped(String affixId) {
        equipped.add(affixId);
    }

    public Set<String> equipped() {
        return Set.copyOf(equipped);
    }

    // ---------------- 一次性 ----------------

    public boolean hasUsedOnce(String affixId) {
        return usedOnce.contains(affixId);
    }

    /**
     * **原子地**尝试用掉一次机会。
     *
     * @return true = 本次成功占用（调用方可以执行效果）；false = 已经用过了
     */
    public boolean useOnceIfAvailable(String affixId) {
        return usedOnce.add(affixId);
    }

    // ---------------- 冷却 ----------------

    public boolean isOnCooldown(String affixId, long now) {
        Long until = cooldowns.get(affixId);
        return until != null && now < until;
    }

    public void setCooldown(String affixId, long untilTick) {
        cooldowns.put(affixId, untilTick);
    }

    // ---------------- buff（带到期 tick） ----------------

    public void setBuff(String key, long untilTick) {
        buffs.put(key, untilTick);
    }

    public boolean hasBuff(String key, long now) {
        Long until = buffs.get(key);
        return until != null && now < until;
    }

    public long buffUntil(String key) {
        return buffs.getOrDefault(key, 0L);
    }

    public void clearBuff(String key) {
        buffs.remove(key);
    }

    // ---------------- 计数器 ----------------

    public long counter(String key) {
        return counters.getOrDefault(key, 0L);
    }

    public void setCounter(String key, long value) {
        counters.put(key, value);
    }

    /** 原子自增并返回自增后的值。 */
    public long addCounter(String key, long delta) {
        return counters.merge(key, delta, Long::sum);
    }

    /** 清掉所有已到期的 buff / 冷却。
     *
     * <p>由心跳每 5 tick 调一次 —— 不做的话 map 会随对局无限增长。
     * ⚠️ **不动计数器**（见 {@link #counters} 的说明）。
     */
    public void prune(long now) {
        buffs.entrySet().removeIf(e -> e.getValue() <= now);
        cooldowns.entrySet().removeIf(e -> e.getValue() <= now);
    }

    /** 该实体身上是否还有任何状态（用于心跳里顺手回收空壳）。 */
    public boolean isEmpty() {
        return usedOnce.isEmpty() && cooldowns.isEmpty() && buffs.isEmpty()
                && equipped.isEmpty() && counters.isEmpty();
    }
}
