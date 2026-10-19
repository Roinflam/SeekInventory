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
import java.util.TreeMap;

/**
 * Curios 饰品模组交互处理器（纯反射实现）。
 *
 * <p>本类面向 Minecraft 1.20.1 + Forge/Mohist + Curios 5.x 环境，同时保持插件本体
 * 可以继续使用 JDK 8 编译，因此不会在源码中直接 import Curios、Forge 或 NMS 类型。</p>
 *
 * <p>Curios 1.20.1 的 {@code CuriosApi#getCuriosInventory} 返回 Forge 的
 * {@code LazyOptional<ICuriosItemHandler>}，并不是 JDK 的 {@link Optional}。旧实现把返回值
 * 直接按 {@link Optional} 判断，导致正常的 {@code LazyOptional} 被当成“不支持”，最终始终
 * 返回空饰品列表且不抛异常。当前实现会通过反射调用 {@code LazyOptional#resolve()}，再取得
 * 其中真正的 {@code ICuriosItemHandler}。</p>
 *
 * <p>线程要求：Curios capability、NMS 玩家实体以及 CraftBukkit 物品转换都只允许在服务器
 * 主线程读取。调用方如果从异步线程调用，会直接抛出异常，避免在 Mohist 上出现并发读取导致的
 * 随机空快照或状态不一致。</p>
 *
 * @author Tany天炎
 */
public class CuriosHandler {

    // ===== CraftBukkit / NMS 反射引用 =====

    /** CraftPlayer.getHandle()：获取 NMS 玩家实体。 */
    private static Method getHandleMethod;

    /** CraftItemStack.asBukkitCopy(NMSItemStack)：NMS 物品转 Bukkit 物品。 */
    private static Method asBukkitCopyMethod;

    /** NMS LivingEntity 类：用于参数签名与运行时类型校验。 */
    private static Class<?> livingEntityClass;

    // ===== Curios / Forge 反射引用 =====

    /** CuriosApi.getCuriosInventory(LivingEntity) -> LazyOptional&lt;ICuriosItemHandler&gt;。 */
    private static Method getCuriosInventoryMethod;

    /** Forge LazyOptional 类型。 */
    private static Class<?> lazyOptionalClass;

    /** LazyOptional.resolve() -> Optional&lt;T&gt;。 */
    private static Method lazyOptionalResolveMethod;

    /** ICuriosItemHandler.getCurios() -> Map&lt;String, ICurioStacksHandler&gt;。 */
    private static Method getCuriosMethod;

    /** ICuriosItemHandler.getSlots() -> int。 */
    private static Method handlerGetSlotsMethod;

    /** ICurioStacksHandler.getStacks() -> IDynamicStackHandler。 */
    private static Method getStacksMethod;

    /** IDynamicStackHandler.getStackInSlot(int) -> NMS ItemStack。 */
    private static Method getStackInSlotMethod;

    /** IDynamicStackHandler.getSlots() -> int。 */
    private static Method dynamicGetSlotsMethod;

    /** 反射初始化是否成功。 */
    private static boolean initialized = false;

    /** CraftItemStack#asNMSCopy(Bukkit ItemStack)，写回饰品槽用 */
    private static Method asNMSCopyMethod;

    /** IItemHandlerModifiable#setStackInSlot(int, NMS ItemStack)，由 IDynamicStackHandler 继承 */
    private static Method setStackInSlotMethod;

    /** 写回所需的反射入口是否齐全（缺了只影响实时编辑，查看和快照照常） */
    private static boolean editSupported = false;

    static {
        try {
            // 动态获取 CraftBukkit 版本号，例如 v1_20_R1。
            String packageName = Bukkit.getServer().getClass().getPackage().getName();
            String[] packageParts = packageName.split("\\.");
            if (packageParts.length < 4 || packageParts[3] == null || packageParts[3].isEmpty()) {
                throw new IllegalStateException("无法识别 CraftBukkit 版本化包路径: " + packageName);
            }
            String version = packageParts[3];

            Class<?> craftPlayerClass = Class.forName(
                    "org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
            getHandleMethod = craftPlayerClass.getMethod("getHandle");

            Class<?> craftItemStackClass = Class.forName(
                    "org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");
            Class<?> nmsItemStackClass = Class.forName("net.minecraft.world.item.ItemStack");
            asBukkitCopyMethod = craftItemStackClass.getMethod("asBukkitCopy", nmsItemStackClass);

            // NMS LivingEntity 只用于反射签名和类型校验，不直接调用其方法。
            livingEntityClass = Class.forName("net.minecraft.world.entity.LivingEntity");

            // Curios API。
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            getCuriosInventoryMethod = curiosApiClass.getMethod("getCuriosInventory", livingEntityClass);

            // Forge LazyOptional。Curios 1.20.1 的 getCuriosInventory 正是返回该类型。
            lazyOptionalClass = Class.forName("net.minecraftforge.common.util.LazyOptional");
            lazyOptionalResolveMethod = lazyOptionalClass.getMethod("resolve");

            Class<?> itemHandlerClass = Class.forName(
                    "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
            getCuriosMethod = itemHandlerClass.getMethod("getCurios");
            handlerGetSlotsMethod = itemHandlerClass.getMethod("getSlots");

            Class<?> stacksHandlerClass = Class.forName(
                    "top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler");
            getStacksMethod = stacksHandlerClass.getMethod("getStacks");

            Class<?> dynamicHandlerClass = Class.forName(
                    "top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler");
            // getStackInSlot / getSlots 由接口或其 Forge 父接口公开，getMethod 会向父接口查找。
            getStackInSlotMethod = dynamicHandlerClass.getMethod("getStackInSlot", int.class);
            dynamicGetSlotsMethod = dynamicHandlerClass.getMethod("getSlots");

            initialized = true;

            try {
                asNMSCopyMethod = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class);
                setStackInSlotMethod = dynamicHandlerClass.getMethod("setStackInSlot", int.class, nmsItemStackClass);
                editSupported = true;
            } catch (Throwable t) {
                editSupported = false;
                Bukkit.getLogger().warning("[SeekInventory] Curios 写回入口初始化失败，饰品栏只能查看不能编辑: "
                        + describeThrowable(t));
            }
        } catch (Throwable t) {
            initialized = false;
            Bukkit.getLogger().warning("[SeekInventory] Curios 反射初始化失败: " + describeThrowable(t));
        }
    }

    /**
     * 检查 Curios 反射处理器是否已成功初始化。
     *
     * @return true 表示运行环境中的 Curios / Forge / CraftBukkit 反射入口均已找到
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * 获取玩家所有非空 Curios 饰品（仅普通装备槽，不含 Cosmetic 装饰槽）。
     *
     * <p>读取流程：</p>
     * <ol>
     *   <li>取得 CraftPlayer 对应的 NMS LivingEntity；</li>
     *   <li>调用 CuriosApi.getCuriosInventory；</li>
     *   <li>把 Forge LazyOptional 通过 resolve() 解包为 JDK Optional；</li>
     *   <li>遍历每种 Curios 类型的普通装备槽；</li>
     *   <li>把 NMS ItemStack 转为 Bukkit ItemStack 后收集非空物品。</li>
     * </ol>
     *
     * <p>顶层 capability/API 读取失败属于真正的快照失败，会抛出异常给调用方，防止把故障
     * 错误地保存成“玩家饰品栏为空”。单个槽位转换失败时会跳过该槽并输出一次汇总警告，其他
     * 正常槽位仍会被保留。</p>
     *
     * @param player Bukkit 玩家对象，不可为 null
     * @return 所有非空饰品的 Bukkit ItemStack 列表，合法空饰品栏返回空列表，永不返回 null
     * @throws IllegalArgumentException 玩家对象为 null
     * @throws IllegalStateException 当前线程不是主线程、反射未初始化、capability 缺失或读取失败
     */
    public static List<ItemStack> getCuriosItems(Player player) {
        validateReadContext(player);

        List<ItemStack> result = new ArrayList<>();
        try {
            Object handler = getCuriosHandler(player);

            // ICuriosItemHandler.getCurios() -> Map<String, ICurioStacksHandler>
            Object curiosObj = getCuriosMethod.invoke(handler);
            if (!(curiosObj instanceof Map)) {
                throw new IllegalStateException("ICuriosItemHandler#getCurios 返回了非 Map 类型: "
                        + className(curiosObj));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> curios = (Map<String, Object>) curiosObj;

            int failedSlots = 0;
            String firstFailure = null;

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

                int slots = ((Number) dynamicGetSlotsMethod.invoke(dynamicStacks)).intValue();
                if (slots < 0) {
                    throw new IllegalStateException("Curios 槽位数量非法: " + slots
                            + "，类型=" + entry.getKey());
                }

                for (int slot = 0; slot < slots; slot++) {
                    try {
                        Object nmsStack = getStackInSlotMethod.invoke(dynamicStacks, slot);
                        if (nmsStack == null) {
                            continue;
                        }

                        ItemStack bukkitStack = (ItemStack) asBukkitCopyMethod.invoke(null, nmsStack);
                        if (bukkitStack != null
                                && bukkitStack.getType() != Material.AIR
                                && bukkitStack.getAmount() > 0) {
                            result.add(bukkitStack);
                        }
                    } catch (Exception e) {
                        failedSlots++;
                        if (firstFailure == null) {
                            firstFailure = "类型=" + entry.getKey() + ", 槽位=" + slot
                                    + ", 原因=" + describeThrowable(e);
                        }
                    }
                }
            }

            if (failedSlots > 0) {
                Bukkit.getLogger().warning("[SeekInventory] 玩家 " + player.getName()
                        + " 的 Curios 有 " + failedSlots + " 个槽位读取失败，已跳过；首次失败: "
                        + firstFailure);
            }

            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("获取玩家 " + player.getName()
                    + " 的 Curios 饰品失败: " + describeThrowable(e), e);
        }
    }

    /**
     * 获取玩家当前 Curios 普通饰品槽位总数。
     *
     * @param player Bukkit 玩家对象，不可为 null
     * @return 饰品槽位总数；读取失败时返回 0，并在控制台打印原因
     * @throws IllegalArgumentException 玩家对象为 null
     */
    public static int getCuriosSlotCount(Player player) {
        if (player == null) {
            throw new IllegalArgumentException("player 不能为 null");
        }
        if (!initialized) {
            Bukkit.getLogger().warning("[SeekInventory] 无法统计 Curios 槽位：反射处理器未初始化");
            return 0;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getLogger().warning("[SeekInventory] 无法统计 Curios 槽位：调用发生在异步线程");
            return 0;
        }

        try {
            Object handler = getCuriosHandler(player);
            return ((Number) handlerGetSlotsMethod.invoke(handler)).intValue();
        } catch (Exception e) {
            Bukkit.getLogger().warning("[SeekInventory] 获取玩家 " + player.getName()
                    + " 的 Curios 槽位数量失败: " + describeThrowable(e));
            return 0;
        }
    }

    /**
     * 饰品栏里的一个普通装备槽（含空槽）
     */
    public static final class CurioSlot {

        /** 饰品类型标识，如 ring、necklace */
        private final String identifier;

        /** 该类型内的槽位序号 */
        private final int index;

        /** 槽内物品，空槽为 null */
        private final ItemStack item;

        CurioSlot(String identifier, int index, ItemStack item) {
            this.identifier = identifier;
            this.index = index;
            this.item = item;
        }

        public String getIdentifier() {
            return identifier;
        }

        public int getIndex() {
            return index;
        }

        public ItemStack getItem() {
            return item;
        }
    }

    /**
     * 写回所需的反射入口是否齐全
     *
     * @return true=可以实时编辑饰品栏
     */
    public static boolean isEditSupported() {
        return initialized && editSupported;
    }

    /**
     * 列出玩家全部普通饰品槽（含空槽），按类型标识排序、同类型按槽位序号排列，
     * 保证同一玩家多次读取时顺序稳定，界面格子才能和槽位一一对上。
     *
     * @param player 在线玩家
     * @return 槽位列表，永不为 null
     * @throws IllegalStateException 不在主线程、反射未初始化或读取失败
     */
    public static List<CurioSlot> getCurioSlots(Player player) {
        validateReadContext(player);

        List<CurioSlot> result = new ArrayList<>();
        try {
            Map<String, Object> curios = new TreeMap<>(getCuriosMap(player));
            for (Map.Entry<String, Object> entry : curios.entrySet()) {
                Object dynamicStacks = entry.getValue() == null ? null : getStacksMethod.invoke(entry.getValue());
                if (dynamicStacks == null) {
                    continue;
                }
                int slots = ((Number) dynamicGetSlotsMethod.invoke(dynamicStacks)).intValue();
                for (int slot = 0; slot < slots; slot++) {
                    ItemStack item = null;
                    Object nmsStack = getStackInSlotMethod.invoke(dynamicStacks, slot);
                    if (nmsStack != null) {
                        ItemStack bukkitStack = (ItemStack) asBukkitCopyMethod.invoke(null, nmsStack);
                        if (bukkitStack != null && bukkitStack.getType() != Material.AIR
                                && bukkitStack.getAmount() > 0) {
                            item = bukkitStack;
                        }
                    }
                    result.add(new CurioSlot(entry.getKey(), slot, item));
                }
            }
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取玩家 " + player.getName()
                    + " 的 Curios 槽位失败: " + describeThrowable(e), e);
        }
    }

    /**
     * 把物品放进玩家的某个饰品槽（覆盖原有物品）
     *
     * <p>直接走 setStackInSlot，不做「这件物品能不能戴在这个槽」的校验，由管理员自己把关。
     * Curios 每 tick 会比对槽位变化，自动触发穿戴 / 卸下效果并同步给客户端。</p>
     *
     * @param player     在线玩家
     * @param identifier 饰品类型标识
     * @param index      槽位序号
     * @param item       要放入的物品，null 或空气表示清空
     * @throws IllegalStateException 不支持写回、槽位不存在或写入失败
     */
    public static void setCurio(Player player, String identifier, int index, ItemStack item) {
        validateReadContext(player);
        if (!editSupported) {
            throw new IllegalStateException("Curios 写回入口未初始化");
        }

        try {
            Object stacksHandler = getCuriosMap(player).get(identifier);
            Object dynamicStacks = stacksHandler == null ? null : getStacksMethod.invoke(stacksHandler);
            if (dynamicStacks == null) {
                throw new IllegalStateException("饰品类型不存在: " + identifier);
            }
            int slots = ((Number) dynamicGetSlotsMethod.invoke(dynamicStacks)).intValue();
            if (index < 0 || index >= slots) {
                throw new IllegalStateException("饰品槽位不存在: " + identifier + "#" + index);
            }
            ItemStack source = item == null ? new ItemStack(Material.AIR) : item;
            Object nmsStack = asNMSCopyMethod.invoke(null, source);
            setStackInSlotMethod.invoke(dynamicStacks, index, nmsStack);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("写入玩家 " + player.getName()
                    + " 的 Curios 槽位失败: " + describeThrowable(e), e);
        }
    }

    /**
     * 取得 ICuriosItemHandler#getCurios() 的结果（类型标识 -&gt; ICurioStacksHandler）
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getCuriosMap(Player player) throws Exception {
        Object curiosObj = getCuriosMethod.invoke(getCuriosHandler(player));
        if (!(curiosObj instanceof Map)) {
            throw new IllegalStateException("ICuriosItemHandler#getCurios 返回了非 Map 类型: "
                    + className(curiosObj));
        }
        return (Map<String, Object>) curiosObj;
    }

    /**
     * 获取玩家的 ICuriosItemHandler。
     *
     * <p>同时兼容 Curios 1.20.1 使用的 Forge LazyOptional，以及极少数旧适配代码可能返回的
     * JDK Optional。这样既修复当前问题，也避免未来同一插件在不同 Curios 构建间切换时再次
     * 因包装类型不同而静默得到空结果。</p>
     *
     * @param player Bukkit 玩家
     * @return 已解包的 ICuriosItemHandler，永不返回 null
     * @throws Exception 反射调用失败
     */
    private static Object getCuriosHandler(Player player) throws Exception {
        Object nmsPlayer = getHandleMethod.invoke(player);
        if (nmsPlayer == null || !livingEntityClass.isInstance(nmsPlayer)) {
            throw new IllegalStateException("无法取得有效的 NMS LivingEntity: " + className(nmsPlayer));
        }

        Object optionalObj = getCuriosInventoryMethod.invoke(null, nmsPlayer);
        Object handler = unwrapOptional(optionalObj);
        if (handler == null) {
            throw new IllegalStateException("玩家 Curios capability 不存在或尚未附加");
        }
        return handler;
    }

    /**
     * 解包 Curios API 返回的 Optional 包装器。
     *
     * @param optionalObj CuriosApi.getCuriosInventory 的返回值
     * @return 包装器中的对象；Optional 为空时返回 null
     * @throws Exception LazyOptional.resolve 反射调用失败
     */
    private static Object unwrapOptional(Object optionalObj) throws Exception {
        if (optionalObj == null) {
            return null;
        }

        // 兼容可能直接返回 JDK Optional 的实现。
        if (optionalObj instanceof Optional) {
            Optional<?> optional = (Optional<?>) optionalObj;
            return optional.isPresent() ? optional.get() : null;
        }

        // Curios 5.x / Forge 1.20.1 的标准路径：LazyOptional -> resolve() -> Optional。
        if (lazyOptionalClass != null && lazyOptionalClass.isInstance(optionalObj)) {
            Object resolvedObj = lazyOptionalResolveMethod.invoke(optionalObj);
            if (!(resolvedObj instanceof Optional)) {
                throw new IllegalStateException("LazyOptional#resolve 返回了非 Optional 类型: "
                        + className(resolvedObj));
            }
            Optional<?> resolved = (Optional<?>) resolvedObj;
            return resolved.isPresent() ? resolved.get() : null;
        }

        throw new IllegalStateException("CuriosApi#getCuriosInventory 返回了不支持的包装类型: "
                + optionalObj.getClass().getName());
    }

    /**
     * 校验 Curios 读取上下文。
     *
     * @param player Bukkit 玩家
     * @throws IllegalArgumentException 玩家为空
     * @throws IllegalStateException 反射未初始化或当前不是主线程
     */
    private static void validateReadContext(Player player) {
        if (player == null) {
            throw new IllegalArgumentException("player 不能为 null");
        }
        if (!initialized) {
            throw new IllegalStateException("Curios 反射处理器未初始化，请查看更早的控制台错误");
        }
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Curios capability 只能在服务器主线程读取");
        }
    }

    /**
     * 获取对象运行时类型名称，便于诊断反射返回值异常。
     *
     * @param object 任意对象
     * @return 类型全限定名；对象为空时返回 null
     */
    private static String className(Object object) {
        return object == null ? "null" : object.getClass().getName();
    }

    /**
     * 生成尽量有用的异常描述。
     *
     * @param throwable 异常或错误
     * @return 包含异常类型与消息的描述文本
     */
    private static String describeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "Unknown";
        }
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isEmpty()) {
            return current.getClass().getName();
        }
        return current.getClass().getName() + ": " + message;
    }
}
