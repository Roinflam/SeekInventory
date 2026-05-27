package com.flameforge.seekinventory.curios;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Curios 饰品模组交互处理器
 *
 * 重要：此类直接引用 Curios API 类，仅在 {@link CuriosSupport#isAvailable()} 返回 true 时才可调用。
 * JVM 仅在首次引用此类时才加载它，因此只要调用方正确使用 CuriosSupport 守卫，
 * Curios 不存在时此类永远不会被加载，不会触发 ClassNotFoundException。
 *
 * 注意：Mohist 混合服运行时 NMS 方法名与开发环境不同（SRG 映射），
 * 因此此类绝不直接调用任何 NMS 类的方法（如 ItemStack.isEmpty()），
 * 所有物品操作一律先通过 CraftItemStack.asBukkitCopy() 转换为 Bukkit ItemStack 后再处理。
 *
 * @author Tany天炎
 */
public class CuriosHandler {

    /** CraftPlayer.getHandle() 反射引用 */
    private static Method getHandleMethod;

    /** CraftItemStack.asBukkitCopy(NMSItemStack) 反射引用 */
    private static Method asBukkitCopyMethod;

    /** 反射初始化是否成功 */
    private static boolean initialized = false;

    static {
        try {
            // 动态获取 CraftBukkit 版本号（兼容不同 Mohist 构建）
            String packageName = Bukkit.getServer().getClass().getPackage().getName();
            String version = packageName.split("\\.")[3];

            Class<?> craftPlayerClass = Class.forName(
                    "org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
            getHandleMethod = craftPlayerClass.getMethod("getHandle");

            Class<?> craftItemStackClass = Class.forName(
                    "org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");

            // 获取 NMS ItemStack 类引用
            Class<?> nmsItemStackClass = Class.forName("net.minecraft.world.item.ItemStack");
            asBukkitCopyMethod = craftItemStackClass.getMethod("asBukkitCopy", nmsItemStackClass);

            initialized = true;
        } catch (Exception e) {
            Bukkit.getLogger().warning("[SeekInventory] Curios 反射初始化失败: " + e.getMessage());
        }
    }

    /**
     * 获取玩家所有非空饰品物品（仅装备槽，不含装饰槽）
     *
     * 实现策略：
     * 1. 通过 Curios API 获取 ICuriosItemHandler（纯 Java 接口调用，无 NMS 方法调用）
     * 2. IDynamicStackHandler.getStackInSlot(i) 返回的是 NMS ItemStack
     * 3. 不对返回值调用任何方法（避免 SRG 映射问题），直接传给 CraftItemStack.asBukkitCopy()
     * 4. 在 Bukkit 侧判空和收集
     *
     * @param player Bukkit 玩家对象
     * @return 所有非空饰品的 Bukkit ItemStack 列表，获取失败返回空列表
     */
    public static List<ItemStack> getCuriosItems(Player player) {
        List<ItemStack> result = new ArrayList<>();
        if (!initialized) {
            return result;
        }

        try {
            // 通过反射获取 NMS 玩家实体
            Object nmsPlayer = getHandleMethod.invoke(player);
            if (nmsPlayer == null) {
                return result;
            }

            // Mohist 下 getHandle() 返回的对象继承自 NMS LivingEntity，可以直接强转
            if (!(nmsPlayer instanceof net.minecraft.world.entity.LivingEntity livingEntity)) {
                return result;
            }

            // 通过 Curios API 获取饰品数据
            CuriosApi.getCuriosInventory(livingEntity).ifPresent(handler -> {
                Map<String, ICurioStacksHandler> curios = handler.getCurios();

                for (Map.Entry<String, ICurioStacksHandler> entry : curios.entrySet()) {
                    IDynamicStackHandler stacks = entry.getValue().getStacks();

                    for (int i = 0; i < stacks.getSlots(); i++) {
                        try {
                            // getStackInSlot 返回 NMS ItemStack
                            // 绝不调用它的任何方法（isEmpty 等），直接通过反射转 Bukkit
                            Object nmsStack = stacks.getStackInSlot(i);

                            if (nmsStack == null) {
                                continue;
                            }

                            // 通过反射调用 CraftItemStack.asBukkitCopy(nmsStack)
                            ItemStack bukkitStack = (ItemStack) asBukkitCopyMethod.invoke(null, nmsStack);

                            // 在 Bukkit 侧判空（安全，不涉及 NMS 映射）
                            if (bukkitStack != null && bukkitStack.getType() != Material.AIR
                                    && bukkitStack.getAmount() > 0) {
                                result.add(bukkitStack);
                            }
                        } catch (Exception ignored) {
                            // 单个槽位转换失败不影响其他槽位
                        }
                    }
                }
            });
        } catch (Exception e) {
            Bukkit.getLogger().warning("[SeekInventory] 获取 Curios 饰品失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取玩家当前饰品槽位总数
     *
     * @param player Bukkit 玩家对象
     * @return 饰品槽位总数，获取失败返回 0
     */
    public static int getCuriosSlotCount(Player player) {
        if (!initialized) {
            return 0;
        }

        try {
            Object nmsPlayer = getHandleMethod.invoke(player);
            if (!(nmsPlayer instanceof net.minecraft.world.entity.LivingEntity livingEntity)) {
                return 0;
            }

            return CuriosApi.getCuriosInventory(livingEntity)
                    .map(handler -> handler.getSlots())
                    .orElse(0);
        } catch (Exception e) {
            return 0;
        }
    }
}