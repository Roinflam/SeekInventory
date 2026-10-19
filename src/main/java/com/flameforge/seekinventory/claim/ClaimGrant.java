package com.flameforge.seekinventory.claim;

/**
 * 领取授权数据模型
 *
 * <p>代表管理员发放给某位玩家、针对某一种快照类型（背包 / 末影箱 / 饰品栏）的领取额度。
 * 额度以「次数」计量：玩家每 Shift+左键领取一个槽位的物品（无论该槽位是 1 个还是一组），
 * 都消耗 1 次额度。</p>
 *
 * <p>线程安全说明：{@code remaining} 字段由 {@link ClaimManager} 通过对本对象加锁
 * （synchronized）的方式读写，禁止外部直接修改。</p>
 *
 * @author Tany天炎
 */
public class ClaimGrant {

    /** 被授权的玩家名（保存管理员输入时解析出的规范大小写） */
    private final String playerName;

    /** 授权类型：inventory / enderchest / curios */
    private final String grantType;

    /** 剩余可领取次数 */
    private int remaining;

    /** 本次授权发放的总次数（用于日志与展示） */
    private final int total;

    /** 发放者名称（管理员名 / CONSOLE） */
    private final String granter;

    /** 发放时间（秒级时间戳） */
    private final long createdAt;

    /** 过期时间（秒级时间戳） */
    private final long expireAt;

    /**
     * 构造函数
     *
     * @param playerName 被授权玩家名，不可为 null
     * @param grantType  授权类型，取值见 {@link com.flameforge.seekinventory.config.DataManager}
     * @param remaining  剩余次数
     * @param total      总次数
     * @param granter    发放者名称
     * @param createdAt  发放时间（秒级时间戳）
     * @param expireAt   过期时间（秒级时间戳）
     */
    public ClaimGrant(String playerName, String grantType, int remaining, int total,
                      String granter, long createdAt, long expireAt) {
        this.playerName = playerName;
        this.grantType = grantType;
        this.remaining = remaining;
        this.total = total;
        this.granter = granter;
        this.createdAt = createdAt;
        this.expireAt = expireAt;
    }

    /**
     * 判断该授权是否已过期
     *
     * @return true=已过期
     */
    public boolean isExpired() {
        return System.currentTimeMillis() / 1000L >= expireAt;
    }

    /**
     * 判断该授权当前是否可用（未过期且仍有剩余次数）
     *
     * @return true=可用
     */
    public synchronized boolean isUsable() {
        return remaining > 0 && !isExpired();
    }

    /**
     * 获取剩余秒数（已过期返回 0）
     *
     * @return 距离过期的剩余秒数
     */
    public long getRemainingSeconds() {
        long left = expireAt - System.currentTimeMillis() / 1000L;
        return Math.max(0L, left);
    }

    // ===== Getter / 受控 Setter =====

    public String getPlayerName() {
        return playerName;
    }

    public String getGrantType() {
        return grantType;
    }

    public synchronized int getRemaining() {
        return remaining;
    }

    /**
     * 设置剩余次数（仅供 {@link ClaimManager} 内部调用）
     *
     * @param remaining 新的剩余次数
     */
    synchronized void setRemaining(int remaining) {
        this.remaining = remaining;
    }

    public int getTotal() {
        return total;
    }

    public String getGranter() {
        return granter;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpireAt() {
        return expireAt;
    }
}
