package com.flameforge.seekinventory.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 1.7.10 / 1.12.2 上没有的材质名和方法，在这里按版本换成能用的。
 */
public final class VersionCompat {

    /** 1.12 及以前叫 WATCH */
    public static final Material CLOCK = firstMaterial("CLOCK", "WATCH");
    /** 1.11~1.12 叫 TOTEM，1.7.10 没有图腾，用下界之星顶上 */
    public static final Material TOTEM = firstMaterial("TOTEM_OF_UNDYING", "TOTEM", "NETHER_STAR");

    private static final boolean STORAGE_CONTENTS = hasMethod(PlayerInventory.class, "getStorageContents");
    private static final boolean TITLE = hasMethod(Player.class, "sendTitle",
            String.class, String.class, int.class, int.class, int.class);

    private VersionCompat() {
    }

    /** 背包主存储 36 格；1.9 之前 getContents 本来就只有这 36 格。 */
    public static ItemStack[] getStorageContents(PlayerInventory inventory) {
        return STORAGE_CONTENTS ? inventory.getStorageContents() : inventory.getContents();
    }

    /** 没有标题的版本直接跳过，同一条提示已经发在聊天栏里。 */
    public static void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        if (TITLE) {
            player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
        }
    }

    private static Material firstMaterial(String... names) {
        for (String name : names) {
            Material material = Material.getMaterial(name);
            if (material != null) {
                return material;
            }
        }
        return Material.STONE;
    }

    private static boolean hasMethod(Class<?> type, String name, Class<?>... params) {
        try {
            type.getMethod(name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
