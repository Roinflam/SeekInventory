package com.flameforge.seekinventory.claim;

import com.flameforge.seekinventory.SeekInventory;
import com.flameforge.seekinventory.config.DataManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;
import pers.tany.yukinoaapi.interfacepart.other.IString;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 领取行为上报器
 *
 * <p>玩家每完成一次「领取会话」（打开领取界面 → 领取若干物品 → 关闭界面），
 * 汇总为一条通知发送给管理员：</p>
 * <ul>
 *   <li>若当前有在线管理员，立即发送给全部在线管理员，并标记为已上报</li>
 *   <li>若当前无在线管理员，通知持久化在数据库中，等待第一位管理员上线时补发</li>
 * </ul>
 *
 * <p>管理员判定条件：{@code isOp()} 或拥有 {@code si.claim.report} 权限。</p>
 *
 * @author Tany天炎
 */
public class ClaimReporter {

    /** 插件实例 */
    private final SeekInventory plugin;

    /** 时间格式化器（仅在主线程 / 单一异步任务内使用，避免并发格式化） */
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /** 上报权限节点 */
    public static final String REPORT_PERMISSION = "si.claim.report";

    /**
     * 构造函数
     *
     * @param plugin 插件实例
     */
    public ClaimReporter(SeekInventory plugin) {
        this.plugin = plugin;
    }

    /**
     * 注册玩家登录监听器，用于给上线的管理员补发离线期间的领取通知
     */
    public void registerListener() {
        Bukkit.getPluginManager().registerEvents(new JoinListener(), plugin);
    }

    /**
     * 提交一条领取会话日志：入库并尝试上报
     *
     * <p>本方法内部自行切换到异步线程，可在主线程安全调用。</p>
     *
     * @param log 待入库的日志对象（id 传 -1）
     */
    public void submit(final ClaimLog log) {
        // 关服流程中（例如玩家被踢下线触发界面关闭）不能再调度任务，
        // 否则会抛出 IllegalPluginAccessException；此时直接同步写库，保证日志不丢失
        if (!plugin.isEnabled()) {
            plugin.getClaimManager().insertLog(log);
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                final long id = plugin.getClaimManager().insertLog(log);
                if (id < 0) {
                    return;
                }

                final String message = buildMessage(log);

                // 发送动作需回主线程执行
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        List<Player> admins = getOnlineAdmins();
                        if (admins.isEmpty()) {
                            // 无在线管理员，保持 reported = 0，等待管理员上线补发
                            return;
                        }
                        for (Player admin : admins) {
                            admin.sendMessage(IString.color(message));
                        }
                        // 标记已上报（异步写库）
                        final List<Long> ids = new ArrayList<>();
                        ids.add(id);
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                plugin.getClaimManager().markReported(ids);
                            }
                        }.runTaskAsynchronously(plugin);
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 给刚上线的管理员补发全部待上报通知
     *
     * <p>本方法内部自行切换到异步线程。</p>
     *
     * @param admin 管理员玩家
     */
    public void deliverPending(final Player admin) {
        new BukkitRunnable() {
            @Override
            public void run() {
                final List<ClaimLog> pending = plugin.getClaimManager().getUnreportedLogs();
                if (pending.isEmpty()) {
                    return;
                }

                final List<String> messages = new ArrayList<>();
                final List<Long> ids = new ArrayList<>();
                for (ClaimLog log : pending) {
                    messages.add(buildMessage(log));
                    ids.add(log.getId());
                }

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!admin.isOnline()) {
                            return;
                        }

                        Map<String, String> ph = new HashMap<>();
                        ph.put("count", String.valueOf(messages.size()));
                        admin.sendMessage(IString.color(
                                plugin.getLanguageManager().getMessage("claim.report-pending-header", ph)));

                        for (String message : messages) {
                            admin.sendMessage(IString.color(message));
                        }

                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                plugin.getClaimManager().markReported(ids);
                            }
                        }.runTaskAsynchronously(plugin);
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 构建上报消息文本
     *
     * @param log 日志对象
     * @return 已填充占位符的消息（尚未做颜色码转换）
     */
    private String buildMessage(ClaimLog log) {
        Map<String, String> ph = new HashMap<>();
        ph.put("player", log.getPlayerName());
        ph.put("type", getTypeDisplay(log.getGrantType()));
        ph.put("snapshot", dateFormat.format(new Date(log.getSnapshotTime() * 1000L)));
        ph.put("time", dateFormat.format(new Date(log.getClaimedAt() * 1000L)));
        ph.put("count", String.valueOf(log.getItemCount()));
        ph.put("items", log.getItemSummary());
        ph.put("remaining", String.valueOf(log.getRemainingAfter()));
        return plugin.getLanguageManager().getMessage("claim.report-line", ph);
    }

    /**
     * 获取快照类型的显示名
     *
     * @param type 类型标识
     * @return 本地化显示名
     */
    public String getTypeDisplay(String type) {
        if (DataManager.TYPE_INVENTORY.equals(type)) {
            return plugin.getLanguageManager().getMessage("snapshot.type-inventory");
        }
        if (DataManager.TYPE_ENDERCHEST.equals(type)) {
            return plugin.getLanguageManager().getMessage("snapshot.type-enderchest");
        }
        return plugin.getLanguageManager().getMessage("snapshot.type-curios");
    }

    /**
     * 获取当前所有在线管理员
     *
     * @return 在线管理员列表（可能为空）
     */
    private List<Player> getOnlineAdmins() {
        List<Player> admins = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOp() || player.hasPermission(REPORT_PERMISSION)) {
                admins.add(player);
            }
        }
        return admins;
    }

    /**
     * 管理员登录监听器：上线时补发离线期间产生的领取通知
     */
    private class JoinListener implements Listener {

        /**
         * 处理玩家加入事件
         *
         * @param evt 加入事件
         */
        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent evt) {
            final Player player = evt.getPlayer();
            if (!player.isOp() && !player.hasPermission(REPORT_PERMISSION)) {
                return;
            }

            // 延迟 3 秒，避免与登录流程中的其他消息混杂
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        deliverPending(player);
                    }
                }
            }.runTaskLater(plugin, 60L);
        }
    }
}
