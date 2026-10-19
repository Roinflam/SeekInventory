package com.flameforge.seekinventory.claim;

/**
 * 领取日志数据模型
 *
 * <p>一条日志代表玩家的一次「领取会话」：从打开领取界面到关闭界面之间的全部领取行为，
 * 汇总为一条记录（避免逐件刷屏）。若本次会话未领取任何物品，则不产生日志。</p>
 *
 * @author Tany天炎
 */
public class ClaimLog {

    /** 数据库自增主键（未入库时为 -1） */
    private final long id;

    /** 领取者玩家名 */
    private final String playerName;

    /** 领取类型：inventory / enderchest / curios */
    private final String grantType;

    /** 来源快照的数据库主键 */
    private final long snapshotId;

    /** 来源快照的记录时间（秒级时间戳） */
    private final long snapshotTime;

    /** 领取物品汇总描述，例如「钻石剑×1、石头×64」 */
    private final String itemSummary;

    /** 本次会话领取的物品件数（即消耗的额度次数） */
    private final int itemCount;

    /** 本次会话结束后该类型剩余的额度次数 */
    private final int remainingAfter;

    /** 领取会话结束时间（秒级时间戳） */
    private final long claimedAt;

    /**
     * 构造函数
     *
     * @param id             数据库主键，未入库传 -1
     * @param playerName     领取者玩家名
     * @param grantType      领取类型
     * @param snapshotId     来源快照主键
     * @param snapshotTime   来源快照记录时间（秒）
     * @param itemSummary    物品汇总描述
     * @param itemCount      领取件数
     * @param remainingAfter 领取后剩余额度
     * @param claimedAt      领取时间（秒）
     */
    public ClaimLog(long id, String playerName, String grantType, long snapshotId, long snapshotTime,
                    String itemSummary, int itemCount, int remainingAfter, long claimedAt) {
        this.id = id;
        this.playerName = playerName;
        this.grantType = grantType;
        this.snapshotId = snapshotId;
        this.snapshotTime = snapshotTime;
        this.itemSummary = itemSummary;
        this.itemCount = itemCount;
        this.remainingAfter = remainingAfter;
        this.claimedAt = claimedAt;
    }

    // ===== Getter =====

    public long getId() {
        return id;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getGrantType() {
        return grantType;
    }

    public long getSnapshotId() {
        return snapshotId;
    }

    public long getSnapshotTime() {
        return snapshotTime;
    }

    public String getItemSummary() {
        return itemSummary;
    }

    public int getItemCount() {
        return itemCount;
    }

    public int getRemainingAfter() {
        return remainingAfter;
    }

    public long getClaimedAt() {
        return claimedAt;
    }
}
