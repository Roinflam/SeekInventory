package com.flameforge.seekinventory.curios;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Curios 饰品模组交互处理器（纯反射实现）
 *
 * == 为什么全部用反射 ==
 * Curios（curios-forge-5.14.1+1.20.1.jar）基于 Java 17 编译（class 版本 61.0），
 * 而本插件为兼容 1.7 ~ 1.20.1 各类服务器需使用 JDK 8 编译（class 版本 52.0）。
 * 一旦在源码中直接 import 任何 Curios 类，JDK 8 的 javac 在解析该 class 时就会报
 * “错误的版本 61.0, 应为 52.0” 而编译失败。
 * 因此本类不 import 任何 Curios / NMS 类，全部通过 Class.forName + 反射在运行时调用，
 * 使编译期完全不依赖 Curios 与 NMS 字节码，JDK 8 即可正常编译。
 *
 * == SRG 映射安全性 ==
 * Mohist 混合服仅对原版 Minecraft（net.minecraft.*）的【方法名 / 字段名】做 SRG 重映射，
 * 第三方模组（Curios、Forge IItemHandler 等）的方法名【不参与重映射】，运行时与开发期一致。
 * 因此反射调用 Curios 自身的方法（getCuriosInventory / getCurios / getStacks /
 * getSlots / getStackInSlot）是安全的，方法名固定不变。
 * NMS 类名（net.minecraft.world.entity.LivingEntity 等）在 Mohist 运行时为官方名，
 * 可通过 Class.forName 获取；本类绝不调用任何 NMS【方法】，NMS ItemStack 一律先经
 * CraftItemStack.asBukkitCopy() 转为 Bukkit ItemStack 再处理。
 *
 * == 类加载安全 ==
 * 本类不直接 import 任何 Curios 类，故 JVM 加载本类自身不要求 Curios 存在；
 * 仅静态块内的 Class.forName 才触及 Curios，初始化失败时静默降级（initialized=false），
 * 所有方法安全返回空结果，不会抛 NoClassDefFoundError。
 * 即便如此，仍应仅在 {@link CuriosSupport#isAvailable()} 返回 true 时调用本类。
 *
 * @author Tany天炎
 */
public class CuriosHandler {

    // ===== CraftBukkit / NMS 反射引用 =====
    /** CraftPlayer.getHandle() —— 获取 NMS 玩家实体 */
    private static Method getHandleMethod;
    /** CraftItemStack.asBukkitCopy(NMSItemStack) —— NMS 物品转 Bukkit 物品 */
    private static Method asBukkitCopyMethod;
    /** NMS LivingEntity 类 —— 用于 isInstance 类型判断及方法签名匹配 */
    private static Class<?> livingEntityClass;

    // ===== Curios API 反射引用（方法引用全部在静态块缓存，调用时不重复查找） =====
    /** CuriosApi.getCuriosInventory(LivingEntity) -> Optional&lt;ICuriosItemHandler&gt; */
    private static Method getCuriosInventoryMethod;
    /** ICuriosItemHandler.getCurios() -> Map&lt;String, ICurioStacksHandler&gt; */
    private static Method getCuriosMethod;
    /** ICuriosItemHandler.getSlots() -> int（饰品槽位总数） */
    private static Method handlerGetSlotsMethod;
    /** ICurioStacksHandler.getStacks() -> IDynamicStackHandler（普通槽，不含装饰槽） */
    private static Method getStacksMethod;
    /** IDynamicStackHandler.getStackInSlot(int) -> NMS ItemStack */
    private static Method getStackInSlotMethod;
    /** IDynamicStackHandler.getSlots() -> int（该处理器槽位数） */
    private static Method dynamicGetSlotsMethod;

    /** 反射初始化是否成功 */
    private static boolean initialized = false;

    static {
        try {
            // --- 动态获取 CraftBukkit 版本号（兼容不同 Mohist 构建）---
            String packageName = Bukkit.getServer().getClass().getPackage().getName();
            String version = packageName.split("\\.")[3];

            Class<?> craftPlayerClass = Class.forName(
                    "org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
            getHandleMethod = craftPlayerClass.getMethod("getHandle");

            Class<?> craftItemStackClass = Class.forName(
                    "org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");
            Class<?> nmsItemStackClass = Class.forName("net.minecraft.world.item.ItemStack");
            asBukkitCopyMethod = craftItemStackClass.getMethod("asBukkitCopy", nmsItemStackClass);

            // --- NMS LivingEntity（Mohist 运行时为官方名）---
            livingEntityClass = Class.forName("net.minecraft.world.entity.LivingEntity");

            // --- Curios API 反射引用（API 包为对外导出，反射调用接口方法安全）---
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            getCuriosInventoryMethod = curiosApiClass.getMethod("getCuriosInventory", livingEntityClass);

            Class<?> itemHandlerClass = Class.forName(
                    "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
            getCuriosMethod = itemHandlerClass.getMethod("getCurios");
            handlerGetSlotsMethod = itemHandlerClass.getMethod("getSlots");

            Class<?> stacksHandlerClass = Class.forName(
                    "top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler");
            getStacksMethod = stacksHandlerClass.getMethod("getStacks");

            Class<?> dynamicHandlerClass = Class.forName(
                    "top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler");
            // getStackInSlot / getSlots 继承自 Forge IItemHandler，getMethod 会搜索超接口
            getStackInSlotMethod = dynamicHandlerClass.getMethod("getStackInSlot", int.class);
            dynamicGetSlotsMethod = dynamicHandlerClass.getMethod("getSlots");

            initialized = true;
        } catch (Throwable t) {
            // Class.forName 触发类初始化可能抛 Error（如 NoClassDefFoundError），故捕获 Throwable
            Bukkit.getLogger().warning("[SeekInventory] Curios 反射初始化失败: " + t.getMessage());
        }
    }

    /**
     * 获取玩家所有非空饰品物品（仅普通装备槽，不含装饰/Cosmetic 槽）
     *
     * 实现策略：
     * 1. 反射调用 Curios API 获取 ICuriosItemHandler（纯反射，无任何 NMS 方法调用）
     * 2. IDynamicStackHandler.getStackInSlot(i) 返回 NMS ItemStack
     * 3. 不对该 NMS ItemStack 调用任何方法（避免 SRG 映射问题），直接经反射转为 Bukkit 物品
     * 4. 在 Bukkit 侧判空与收集
     *
     * @param player Bukkit 玩家对象，不可为 null
     * @return 所有非空饰品的 Bukkit ItemStack 列表；未初始化或获取失败时返回空列表（永不为 null）
     */
    public static List<ItemStack> getCuriosItems(Player player) {
        List<ItemStack> result = new ArrayList<>();
        if (!initialized) {
            return result;
        }

        try {
            // 反射获取 NMS 玩家实体
            Object nmsPlayer = getHandleMethod.invoke(player);
            // 用 Class.isInstance 代替 instanceof，避免编译期直接引用 NMS LivingEntity 类
            if (nmsPlayer == null || !livingEntityClass.isInstance(nmsPlayer)) {
                return result;
            }

            // CuriosApi.getCuriosInventory(LivingEntity) -> Optional<ICuriosItemHandler>
            // Optional 为 JDK 自带类型，可直接强转使用
            Object optionalObj = getCuriosInventoryMethod.invoke(null, nmsPlayer);
            if (!(optionalObj instanceof Optional)) {
                return result;
            }
            Optional<?> inventoryOpt = (Optional<?>) optionalObj;
            if (!inventoryOpt.isPresent()) {
                return result;
            }
            Object handler = inventoryOpt.get();

            // ICuriosItemHandler.getCurios() -> Map<String, ICurioStacksHandler>
            Object curiosObj = getCuriosMethod.invoke(handler);
            if (!(curiosObj instanceof Map)) {
                return result;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> curios = (Map<String, Object>) curiosObj;

            for (Map.Entry<String, Object> entry : curios.entrySet()) {
                Object stacksHandler = entry.getValue();
                if (stacksHandler == null) {
                    continue;
                }

                // ICurioStacksHandler.getStacks() -> IDynamicStackHandler
                Object dynamicStacks = getStacksMethod.invoke(stacksHandler);
                if (dynamicStacks == null) {
                    continue;
                }

                // IDynamicStackHandler.getSlots() -> int（反射返回装箱 Integer，自动拆箱）
                int slots = (Integer) dynamicGetSlotsMethod.invoke(dynamicStacks);

                for (int i = 0; i < slots; i++) {
                    try {
                        // getStackInSlot(i) 返回 NMS ItemStack，绝不调用其任何方法
                        Object nmsStack = getStackInSlotMethod.invoke(dynamicStacks, i);
                        if (nmsStack == null) {
                            continue;
                        }

                        // 反射调用 CraftItemStack.asBukkitCopy(nmsStack)
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
        } catch (Exception e) {
            Bukkit.getLogger().warning("[SeekInventory] 获取 Curios 饰品失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取玩家当前饰品槽位总数
     *
     * @param player Bukkit 玩家对象，不可为 null
     * @return 饰品槽位总数；未初始化、玩家无饰品能力或获取失败时返回 0
     */
    public static int getCuriosSlotCount(Player player) {
        if (!initialized) {
            return 0;
        }

        try {
            Object nmsPlayer = getHandleMethod.invoke(player);
            // 用 Class.isInstance 代替 instanceof，避免编译期直接引用 NMS LivingEntity 类
            if (nmsPlayer == null || !livingEntityClass.isInstance(nmsPlayer)) {
                return 0;
            }

            // CuriosApi.getCuriosInventory(LivingEntity) -> Optional<ICuriosItemHandler>
            Object optionalObj = getCuriosInventoryMethod.invoke(null, nmsPlayer);
            if (!(optionalObj instanceof Optional)) {
                return 0;
            }
            Optional<?> inventoryOpt = (Optional<?>) optionalObj;
            if (!inventoryOpt.isPresent()) {
                return 0;
            }

            // ICuriosItemHandler.getSlots() -> int（反射返回装箱 Integer，自动拆箱）
            return (Integer) handlerGetSlotsMethod.invoke(inventoryOpt.get());
        } catch (Exception e) {
            return 0;
        }
    }
}