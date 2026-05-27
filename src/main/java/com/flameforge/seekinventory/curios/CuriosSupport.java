package com.flameforge.seekinventory.curios;

import org.bukkit.Bukkit;

/**
 * Curios 饰品模组支持检测
 * 通过多重类加载检测判断当前环境是否支持 Curios 饰品栏备份功能，完全避免硬依赖
 *
 * 检测流程（任何一步失败都会让 isAvailable() 返回 false）：
 *   1. Curios API 主类是否存在（top.theillusivec4.curios.api.CuriosApi）
 *      - 纯 Bukkit/Spigot/Paper（非模组服）必然在此步失败
 *      - Forge/Mohist 但未安装 Curios 模组也在此步失败
 *   2. NMS Mojang 映射的 LivingEntity 类是否存在
 *      - 仅在 Forge/Mohist/Arclight 等 Mojang 映射环境下存在
 *      - 纯 Bukkit/Spigot 即使 Curios 类被以某种方式塞入也会在此步失败
 *   3. NMS Mojang 映射的 ItemStack 类是否存在
 *      - 用于 CuriosHandler 中通过反射调用 CraftItemStack.asBukkitCopy(NMSItemStack)
 *   4. CraftBukkit 版本化包路径是否可用（如 v1_20_R1）
 *      - Paper 1.20.5+ 已移除版本号子包，会在此步失败
 *      - 部分较老或定制服务端如果包结构不符也会在此步失败
 *
 * 设计目的：
 *   - 支持 1.7 ~ 1.21 范围内的纯 Bukkit/Spigot/Paper 服务器：第 1-2 步必然失败，Curios 功能静默禁用，插件其余功能不受影响
 *   - 仅在 1.20.1 Mohist + Curios 这一目标环境下才完整通过 4 步检测
 *   - 其他模组服或版本不匹配场景：任意步骤失败即静默禁用，不打印任何日志，不抛任何异常
 *
 * 使用方式：
 *   1. 先调用 CuriosSupport.isAvailable() 判断
 *   2. 仅在返回 true 时才调用 CuriosHandler 中的方法
 *   3. 由于 JVM 的类延迟加载机制，CuriosHandler 类只有在被实际调用时才会加载，
 *      不会因 import 语句而触发 ClassNotFoundException
 *
 * @author Tany天炎
 */
public class CuriosSupport {

    /** Curios 饰品栏备份功能是否在当前环境可用 */
    private static final boolean available;

    static {
        boolean found = false;
        try {
            // 第 1 步：检测 Curios API 主类
            // 纯 Bukkit/Spigot/Paper（非模组服）必然抛 ClassNotFoundException
            Class.forName("top.theillusivec4.curios.api.CuriosApi");

            // 第 2 步：检测 NMS Mojang 映射的 LivingEntity
            // CuriosHandler 内部使用了 instanceof net.minecraft.world.entity.LivingEntity 强类型判断
            // 必须确保此类存在，否则 CuriosHandler 加载时会触发 NoClassDefFoundError
            Class.forName("net.minecraft.world.entity.LivingEntity");

            // 第 3 步：检测 NMS Mojang 映射的 ItemStack
            // CuriosHandler 通过反射调用 CraftItemStack.asBukkitCopy(NMSItemStack)
            // 此类必须存在才能完成反射调用
            Class.forName("net.minecraft.world.item.ItemStack");

            // 第 4 步：检测 CraftBukkit 版本化包路径
            // 例如：org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer
            // Paper 1.20.5+ 移除了版本号子包（NMS revamp），会导致 CuriosHandler 中的反射失败
            String packageName = Bukkit.getServer().getClass().getPackage().getName();
            String[] parts = packageName.split("\\.");

            // 确保包结构形如 org.bukkit.craftbukkit.<version>，且 <version> 段非空
            if (parts.length >= 4 && parts[3] != null && !parts[3].isEmpty()) {
                String version = parts[3];

                // 验证版本化路径下的 CraftPlayer 和 CraftItemStack 确实存在
                Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
                Class.forName("org.bukkit.craftbukkit." + version + ".inventory.CraftItemStack");

                // 所有检测都通过，标记为可用
                found = true;
            }
        } catch (Throwable t) {
            // 捕获所有异常和错误（ClassNotFoundException、NoClassDefFoundError、
            // ArrayIndexOutOfBoundsException、NullPointerException 等）
            // 任何异常都意味着当前环境不满足 Curios 备份的运行条件
            // 静默失败：不打印任何日志，避免在不支持的环境下打扰用户
            found = false;
        }
        available = found;
    }

    /**
     * 检查 Curios 饰品栏备份功能是否在当前环境可用
     *
     * @return true=当前环境完整支持 Curios（1.20.1 Mohist + Curios）；
     *         false=不支持，所有 Curios 相关功能将被静默禁用，不产生任何报错
     */
    public static boolean isAvailable() {
        return available;
    }
}