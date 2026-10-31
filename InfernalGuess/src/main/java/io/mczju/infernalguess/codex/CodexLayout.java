package io.mczju.infernalguess.codex;

/**
 * 图鉴三个菜单的槽位布局（唯一真相源）。
 *
 * <p>版式**完全仿照 Maggoteers**（`mcplugin` 的 `CodexLayout`）：
 * <ul>
 *   <li>一级（3×9）：只剩两本图鉴，放在中间对称的 11 / 15 格；退出在第 3 行第 9 格（26）。</li>
 *   <li>Boss（怪物）图鉴（5×9）：第 1–4 行第 1 格是行标签（权重区间），
 *       第 2–9 格放最多 8 只怪；第 5 行第 2/8 格 = 上一页/下一页、第 9 格 = 返回。</li>
 *   <li>词条图鉴（5×9）：第 2–4 行第 2–8 格 = 21 条；第 5 行第 2/8 格 = 上一页/下一页、
 *       第 9 格 = 返回。</li>
 * </ul>
 *
 * <p>写成常量而不是散在菜单里，是为了让 {@code CodexLayoutTest} 能钉住版式：
 * **改版式必须先改测试**。
 */
public final class CodexLayout {

    public static final int COLUMNS = 9;

    // ================================================================ 一级菜单（3×9）
    public static final int MAIN_ROWS = 3;
    public static final int MAIN_BOSS = 11;      // 第 2 行第 3 格
    public static final int MAIN_AFFIX = 15;     // 第 2 行第 7 格
    public static final int MAIN_EXIT = 26;      // 第 3 行第 9 格

    // ================================================================ 二级菜单（5×9）
    public static final int SUB_ROWS = 5;
    /** 第 5 行第 2 / 第 8 格：上一页 / 下一页。 */
    public static final int PAGE_PREV = 37;
    public static final int PAGE_NEXT = 43;
    /** 第 5 行第 9 格：返回。两本二级菜单共用。 */
    public static final int SUB_BACK = 44;

    // ================================================================ Boss（怪物）图鉴
    /** 每行第 1 格是权重区间标签。 */
    public static final int BOSS_LABEL_OFFSET = 0;
    /** 行内最多 8 只（第 2–9 格）。 */
    public static final int BOSS_SLOTS_PER_ROW = 8;
    /** 一页最多 4 行。 */
    public static final int BOSS_MAX_ROWS = 4;
    /** 一页最多 4 × 8 = 32 只。 */
    public static final int BOSS_PAGE_SIZE = BOSS_MAX_ROWS * BOSS_SLOTS_PER_ROW;

    // ================================================================ 词条图鉴
    /** 第 2–4 行 × 第 2–8 格 = 21 格（顺序：先行后列）。 */
    public static final int[] AFFIX_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    /** 一页最多 21 条。 */
    public static final int AFFIX_PAGE_SIZE = AFFIX_SLOTS.length;

    private CodexLayout() {
    }

    /** 第 {@code row}（0 基）行第 {@code col}（0 基）列的槽位。 */
    public static int slot(int row, int col) {
        return row * COLUMNS + col;
    }

    /** 第 {@code row} 行第 {@code indexInRow} 个怪物的槽位（第 1 格是标签，所以 +1）。 */
    public static int bossSlot(int row, int indexInRow) {
        return slot(row, BOSS_LABEL_OFFSET + 1 + indexInRow);
    }
}
