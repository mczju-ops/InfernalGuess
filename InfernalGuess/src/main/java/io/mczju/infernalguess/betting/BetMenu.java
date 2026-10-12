package io.mczju.infernalguess.betting;

import io.mczju.infernalguess.InfernalGuessPlugin;
import io.mczju.infernalguess.battle.BattleSide;
import io.mczju.infernalguess.config.DisplayConfig;
import io.mczju.infernalguess.config.GameConfig;
import io.mczju.infernalguess.gui.IconFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 两步式下注界面。
 *
 * <pre>
 *   第一步「选边」：左 / 观望 / 右
 *        ↓ 点左或右
 *   第二步「选金额」：25% / 50% / ALL IN（+ 返回）
 * </pre>
 *
 * <p>点「观望」或在第一步直接确认 → 立刻下注观战。
 *
 * <p>所有图标与文案取自 {@code display.yml}（{@code icons.side.*} / {@code icons.amount.*}），
 * 金额图标里的 {@code {stake}} / {@code {win}} / {@code {line}} 由本类按玩家当前资金实时算出。
 */
public final class BetMenu implements InventoryHolder {

    /** 下注结果回调。 */
    public interface Handler {
        /**
         * @return {@code true} 表示接受（界面会关闭并由调用方给反馈）
         */
        boolean onBet(Player player, Bet bet);
    }

    public enum Step { SIDE, AMOUNT }

    public static final int SIZE = 27;

    /**
     * ★ 两步的三个按钮**共用同一组居中槽位**（第 2 / 4 / 6 列）。
     *
     * <h2>为什么必须是 11 / 13 / 15</h2>
     * 9 格宽的一行，视觉中心是**第 4 列**（槽位 13）。三个按钮各空一格排开，
     * 要让「整组」居中就只有 `11 / 13 / 15` 一种排法 ——
     * 原第二步用的是 `10 / 12 / 14`（列 1/3/5），整组偏左一格，
     * 与正下方的返回按钮（槽位 22，第 4 列）不对齐，被用户一眼看出「歪的」。
     *
     * <h2>⚠️ 两步共用槽位的前提</h2>
     * {@link #render()} 每次都会**先把 27 格全部填成 filler**，再画当前步骤的按钮，
     * 所以两张页面不会互相残留。**反过来说：任何「在某一步里顺手清掉另一步按钮」
     * 的写法都会误擦自己** —— 实测教训：把 10/12/14 挪到 11/13/15 之后，
     * 原先那几行 `setItem(SLOT_25, filler)` 正好落在刚画好的选边按钮上，
     * 会把按钮擦成空格（编译期毫无提示）。
     */
    private static final int CENTER_LEFT = 11;
    private static final int CENTER_MID = 13;
    private static final int CENTER_RIGHT = 15;

    // 第一步：左 / 观望 / 右
    public static final int SLOT_LEFT = CENTER_LEFT;
    public static final int SLOT_WATCH = CENTER_MID;
    public static final int SLOT_RIGHT = CENTER_RIGHT;
    // 第二步：25% / 50% / ALL IN（与第一步同槽位，见上面的说明）
    public static final int SLOT_25 = CENTER_LEFT;
    public static final int SLOT_50 = CENTER_MID;
    public static final int SLOT_ALL_IN = CENTER_RIGHT;
    /** 返回：第 3 行正中间（第 4 列），与上面那组按钮同一条中轴。 */
    public static final int SLOT_BACK = 22;
    // 公共
    public static final int SLOT_INFO = 4;
    /**
     * ★ 双方怪物的**图标槽**（用户初始需求「下注界面显示双方怪物信息」）。
     *
     * <p>用**最左一列**放左方、**最右一列**放右方 —— 空间位置与阵营一一对应，
     * 玩家一眼能看出「这一列是左边要派出的怪」。
     * 每列 3 格，正好覆盖「每方最多 3 个刷怪点」的上限。
     *
     * <p>刻意不用中间区域：那里是左/观望/右与金额按钮，避免误点。
     */
    public static final int[] MOB_SLOTS_LEFT = {18, 9, 0};
    public static final int[] MOB_SLOTS_RIGHT = {26, 17, 8};

    private final InfernalGuessPlugin plugin;
    private final Player player;
    private final Handler handler;
    /** 下注者本人（资金 / 生命 / 护盾都从这里取，避免参数越传越多）。 */
    private final io.mczju.infernalguess.game.GamePlayer self;
    private final long killLine;
    /** 本局模式；终极模式跳过「选金额」、禁用观望、不显示资金（Q52 + 用户第二轮反馈）。 */
    private final io.mczju.infernalguess.config.GameMode mode;
    /** 本回合剧本 —— 用于在选边按钮上展示阵容与词条。 */
    private final io.mczju.infernalguess.run.RoundPlan plan;
    /** ⚠️ 只创建一次：切换步骤时**就地重绘**，否则玩家看到的还是旧的那个 Inventory。 */
    private final Inventory inventory;

    private Step step = Step.SIDE;
    private BattleSide pendingSide;

    public BetMenu(InfernalGuessPlugin plugin, Player player, Handler handler,
                   io.mczju.infernalguess.game.GamePlayer self, long killLine) {
        this(plugin, player, handler, self, killLine, plugin.gameConfig().mode(), null);
    }

    public BetMenu(InfernalGuessPlugin plugin, Player player, Handler handler,
                   io.mczju.infernalguess.game.GamePlayer self, long killLine,
                   io.mczju.infernalguess.config.GameMode mode,
                   io.mczju.infernalguess.run.RoundPlan plan) {
        this.plugin = plugin;
        this.player = player;
        this.handler = handler;
        this.self = self;
        this.killLine = killLine;
        this.mode = mode;
        this.plan = plan;
        String title = plugin.displayConfig().raw("menu.betting-title-side");
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text(title));
        render();
    }

    private long funds() {
        return self == null ? 0 : self.funds();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player player() {
        return player;
    }

    public Step step() {
        return step;
    }

    public BattleSide pendingSide() {
        return pendingSide;
    }

    public Handler handler() {
        return handler;
    }

    // ================= 渲染 =================

    /** 就地重绘。标题无法改变（Bukkit 限制），所以两步共用「下注 · 选择阵营」标题。 */
    private void render() {
        DisplayConfig display = plugin.displayConfig();

        ItemStack filler = IconFactory.filler(display);
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, filler);
        }
        // 信息格：资金模式显示资金 + 斩杀线；终极模式显示生命 + 护盾（**不显示资金**）
        if (mode.usesFunds()) {
            inventory.setItem(SLOT_INFO, IconFactory.make(display, "common.funds", Map.of(
                    "funds", String.valueOf(funds()),
                    "line", String.valueOf(killLine))));
        } else {
            inventory.setItem(SLOT_INFO, IconFactory.make(display, "common.stack", Map.of(
                    "life", String.valueOf(self == null ? 0 : self.life()),
                    "shield", String.valueOf(self == null ? 0 : self.shield()),
                    "shield_until", String.valueOf(plugin.gameConfig().shieldUntilRound()))));
        }

        // ★ 双方怪物的图标（用户初始需求「下注界面显示双方怪物信息」）
        paintMobIcons(display);

        if (step == Step.SIDE) {            // ★ 用户反馈 7.3：玩家有权知道本回合阵容与炒鸡词条 ——
            //   把阵容直接写进「押左/押右」两个按钮的描述里。
            inventory.setItem(SLOT_LEFT, IconFactory.make(display, "side.left", Map.of(),
                    rosterLore(display, true)));
            // ★ 终极模式禁用观望：不显示该按钮（用户第二轮反馈）
            inventory.setItem(SLOT_WATCH, mode.allowsWatch()
                    ? IconFactory.make(display, "side.watch", Map.of())
                    : filler);
            inventory.setItem(SLOT_RIGHT, IconFactory.make(display, "side.right", Map.of(),
                    rosterLore(display, false)));
            // ⚠️ 这里**不要**再「顺手清掉第二步的按钮」：两步共用同一组居中槽位，
            //    那样写会把刚画好的选边按钮擦成空格。开头那次全量 filler 已经够了。
        } else {
            // ★ 斩杀线约束（用户需求第 1 条）：资金低于斩杀线时只能观望或 ALL IN
            boolean restricted = mode.usesKillLine() && funds() < killLine;
            if (restricted) {
                inventory.setItem(SLOT_25, filler);
                inventory.setItem(SLOT_50, filler);
            } else {
                inventory.setItem(SLOT_25, amountIcon(display, "amount.percent-25", 0.25));
                inventory.setItem(SLOT_50, amountIcon(display, "amount.percent-50", 0.50));
            }
            inventory.setItem(SLOT_ALL_IN, amountIcon(display, "amount.all-in", 1.0));
            inventory.setItem(SLOT_BACK, IconFactory.make(display, "amount.back", Map.of()));
            // ⚠️ 同理：不要清第一步的按钮（同槽位），开头那次全量 filler 已经清过了。
        }
    }

    /**
     * 生成某一侧的阵容说明行（挂在选边按钮的 lore 末尾）。
     *
     * <p>每一行形如 {@code · 3 × 僵尸 [剧毒 迅捷]} —— 词条用炒鸡的显示名。
     */
    private List<Component> rosterLore(DisplayConfig display, boolean leftSide) {
        if (plan == null) {
            return List.of();
        }
        var side = leftSide ? plan.left() : plan.right();
        if (side == null || side.entries().isEmpty()) {
            return List.of();
        }
        var infernal = plugin.affixRegistry();
        List<Component> out = new ArrayList<>();
        for (var entry : side.entries()) {
            out.add(display.render("battle.roster-entry", Map.of(
                    "amount", String.valueOf(entry.amount()),
                    "name", entry.group().displayName())));
            if (!entry.affixes().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (var a : entry.affixes()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(infernal.displayName(a.id()));
                }
                out.add(display.render("battle.roster-affix", Map.of("list", sb.toString())));
            }
        }
        return out;
    }

    /**
     * ★ **绘制双方怪物的图标** —— 用户初始需求「下注界面显示双方怪物信息」。
     *
     * <h2>为什么必须有它（实测发现的缺陷）</h2>
     * 此前阵容只以**文字 lore** 挂在「押左/押右」按钮上，而
     * {@code display.yml} 的 {@code mob-items}（生物 id → 代表物品）**从未被任何代码读取**
     * —— 也就是说那一整段配置是**死配置**，配套的「每只怪一个图标」UI 完全没有做。
     * 本方法把它接上。
     *
     * <h2>布局</h2>
     * 用**最左一列**放左方、**最右一列**放右方（{@link #MOB_SLOTS_LEFT} /
     * {@link #MOB_SLOTS_RIGHT}）—— 空间位置与阵营一一对应。每列 3 格，
     * 正好覆盖「每方最多 3 个刷怪点」的上限；中间留给按钮，避免误点。
     *
     * <p>两步（选边/选金额）都会画这些图标 —— 玩家在选金额时也需要看着阵容决定押多少。
     */
    private void paintMobIcons(DisplayConfig display) {
        paintMobColumn(display, plan == null ? null : plan.left(), MOB_SLOTS_LEFT);
        paintMobColumn(display, plan == null ? null : plan.right(), MOB_SLOTS_RIGHT);
    }

    private void paintMobColumn(DisplayConfig display,
                                io.mczju.infernalguess.run.RoundPlan.SidePlan side, int[] slots) {
        ItemStack filler = IconFactory.filler(display);
        for (int slot : slots) {
            inventory.setItem(slot, filler);   // 先清干净（重复渲染时不会留残影）
        }
        if (side == null || side.entries() == null || side.entries().isEmpty()) {
            return;
        }
        int i = 0;
        for (var entry : side.entries()) {
            if (i >= slots.length) {
                break;                          // 每方最多 3 个刷怪点，够用
            }
            inventory.setItem(slots[i++], mobIcon(display, entry));
        }
    }

    /** 一只怪一个图标：材质取自 {@code mob-items}，名称/词条取自 display.yml 的既有文案。 */
    private ItemStack mobIcon(DisplayConfig display,
                              io.mczju.infernalguess.run.RoundPlan.RosterEntry entry) {
        var group = entry.group();
        // ★ 材质来自 display.yml 的 mob-items；**缺失时兜底用该生物的刷怪蛋** ——
        //   这样即使 mob-items 没配全，每只怪也有个像样的图标，而不是统一一块石头。
        var material = display.mobItem(group.id(), spawnEggOf(group.entityType()));
        Component name = display.render("battle.roster-entry", Map.of(
                "amount", String.valueOf(entry.amount()),
                "name", group.displayName() == null ? group.id() : group.displayName()));
        List<Component> lore = new ArrayList<>();
        if (!entry.affixes().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (var a : entry.affixes()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(plugin.affixRegistry().displayName(a.id()));
            }
            lore.add(display.render("battle.roster-affix", Map.of("list", sb.toString())));
        }
        return IconFactory.makeWithMaterial(material, name, lore);
    }

    /**
     * 该生物的**刷怪蛋**材质（{@code mob-items} 缺配时的兜底）。
     *
     * <p>用刷怪蛋是因为它对**任何生物**都存在且视觉上直接对应那只怪
     * （组合组如 {@code chicken_jockey} 没有同名刷怪蛋 → 会退回它的**载具**类型，
     * 例如 {@code CHICKEN_SPAWN_EGG}）。取不到就退回石头。
     */
    private static org.bukkit.Material spawnEggOf(org.bukkit.entity.EntityType type) {
        if (type == null) {
            return org.bukkit.Material.STONE;
        }
        var m = org.bukkit.Material.matchMaterial(type.name() + "_SPAWN_EGG");
        return m == null ? org.bukkit.Material.STONE : m;
    }

    /** 金额图标：把本金与获胜后资金算出来填进 lore。 */
    private ItemStack amountIcon(DisplayConfig display, String path, double ratio) {
        GameConfig cfg = plugin.gameConfig();
        long funds = funds();
        long stake = cfg.rounding().apply(funds, ratio);
        boolean allIn = ratio >= 1.0;
        long win = allIn
                ? 2L * funds + killLine
                : funds + stake;
        Map<String, String> ph = new HashMap<>();
        ph.put("stake", String.valueOf(stake));
        ph.put("win", String.valueOf(win));
        ph.put("line", String.valueOf(killLine));
        return IconFactory.make(display, path, ph);
    }

    // ================= 交互 =================

    /**
     * 第一步：选边。
     *
     * <p>资金模式 → 进入第二步「选金额」；
     * **终极模式 → 直接完成下注**（亏损量固定 1 点，选金额没有意义，Q52）。
     */
    public void chooseSide(BattleSide side) {
        if (!mode.needsStakeChoice()) {
            // 比例在终极模式下被忽略，用 1.0 作为哨兵值
            submit(Bet.of(side, 1.0));
            return;
        }
        this.pendingSide = side;
        this.step = Step.AMOUNT;
        render();
    }

    /** 第二步：返回。 */
    public void back() {
        this.pendingSide = null;
        this.step = Step.SIDE;
        render();
    }

    /** 观望：直接下注。 */
    public void watch() {
        submit(Bet.watching());
    }

    /** 第二步：选金额并提交。 */
    public void chooseAmount(double ratio) {
        if (pendingSide == null) {
            return;
        }
        submit(Bet.of(pendingSide, ratio));
    }

    private void submit(Bet bet) {
        boolean accepted = handler != null && handler.onBet(player, bet);
        if (!accepted) {
            // 不在下注阶段（或已结算）→ 用 display.yml 的既有文案提示
            player.sendMessage(plugin.displayConfig().render("menu.betting-closed"));
        }
        player.closeInventory();
    }

    /** 打开界面。 */
    public void open() {
        player.openInventory(inventory);
    }

    /** 便捷入口。 */
    public static void open(InfernalGuessPlugin plugin, Player player, Handler handler,
                            io.mczju.infernalguess.game.GamePlayer self, long killLine) {
        new BetMenu(plugin, player, handler, self, killLine).open();
    }

    /** 便捷入口（指定模式 + 本回合剧本）。 */
    public static void open(InfernalGuessPlugin plugin, Player player, Handler handler,
                            io.mczju.infernalguess.game.GamePlayer self, long killLine,
                            io.mczju.infernalguess.config.GameMode mode,
                            io.mczju.infernalguess.run.RoundPlan plan) {
        new BetMenu(plugin, player, handler, self, killLine, mode, plan).open();
    }

    /** 调试用：描述当前状态。 */
    public String describe() {
        return String.format(Locale.ROOT, "BetMenu[step=%s,side=%s,funds=%d,line=%d]",
                step, pendingSide, funds(), killLine);
    }
}
