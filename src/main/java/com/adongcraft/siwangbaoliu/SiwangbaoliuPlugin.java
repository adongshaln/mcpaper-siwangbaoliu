package com.adongcraft.siwangbaoliu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SiwangbaoliuPlugin extends JavaPlugin implements Listener {
    private static final BlockFace[] HORIZONTAL = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
    private static final int SEARCH_RADIUS = 5;

    private final Map<UUID, Component> pending = new ConcurrentHashMap<>();
    private org.bukkit.NamespacedKey deathChestKey;

    @Override
    public void onEnable() {
        deathChestKey = new org.bukkit.NamespacedKey(this, "death_chest");
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("死亡保留已启用");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) {
            return;
        }
        List<ItemStack> packed = pack(event.getDrops());
        if (packed.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        Block[] placed = placeChests(player.getLocation(), packed.size() > 27);
        if (placed[0] == null) {
            remember(player, Component.text("附近没有能放箱子的位置，物品掉在地上了。", NamedTextColor.RED));
            return;
        }
        Component name = Component.text(player.getName() + "的物品", NamedTextColor.GOLD);
        tag(placed[0], name);
        if (placed[1] != null) {
            tag(placed[1], name);
        }
        List<ItemStack> leftover = store(placed, packed);
        event.getDrops().clear();
        for (ItemStack stack : leftover) {
            placed[0].getWorld().dropItemNaturally(placed[0].getLocation().add(0.5, 0.5, 0.5), stack);
        }
        int x = placed[0].getX();
        int y = placed[0].getY();
        int z = placed[0].getZ();
        String world = placed[0].getWorld().getName();
        String text = leftover.isEmpty()
                ? "你的物品留在原地的箱子里了：" + world + " " + x + " " + y + " " + z
                : "箱子装满了，剩下的掉在旁边：" + world + " " + x + " " + y + " " + z;
        remember(player, Component.text(text, NamedTextColor.GOLD));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(this, () -> deliver(event.getPlayer()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        deliver(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        scheduleEmptyCheck(event.getInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        scheduleEmptyCheck(event.getInventory());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        scheduleEmptyCheck(event.getInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (involvesDeathChest(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isDeathChest(block)) {
            return;
        }
        dropContents(block);
        event.setDropItems(false);
        event.setExpToDrop(0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        salvage(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        salvage(event.blockList());
    }

    private void remember(Player player, Component message) {
        pending.put(player.getUniqueId(), message);
    }

    private void deliver(Player player) {
        Component message = pending.remove(player.getUniqueId());
        if (message != null) {
            player.sendMessage(message);
        }
    }

    private void scheduleEmptyCheck(Inventory inventory) {
        if (!involvesDeathChest(inventory)) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> removeIfEmpty(inventory));
    }

    private void removeIfEmpty(Inventory inventory) {
        if (!isEmpty(inventory) || !involvesDeathChest(inventory)) {
            return;
        }
        List<Block> blocks = deathBlocks(inventory);
        for (HumanEntity viewer : List.copyOf(inventory.getViewers())) {
            viewer.closeInventory();
        }
        for (Block block : blocks) {
            if (isDeathChest(block)) {
                block.setType(Material.AIR);
            }
        }
    }

    private void salvage(List<Block> blocks) {
        Iterator<Block> iterator = blocks.iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (!isDeathChest(block)) {
                continue;
            }
            iterator.remove();
            dropContents(block);
            block.setType(Material.AIR);
        }
    }

    private void dropContents(Block block) {
        if (!(block.getState() instanceof Chest chest)) {
            return;
        }
        Location location = block.getLocation().add(0.5, 0.5, 0.5);
        Inventory inventory = chest.getBlockInventory();
        for (ItemStack stack : inventory.getContents()) {
            if (!isPresent(stack)) {
                continue;
            }
            block.getWorld().dropItemNaturally(location, stack.clone());
        }
        inventory.clear();
    }

    private Block[] placeChests(Location death, boolean needTwo) {
        List<Block> candidates = candidates(death);
        Block first = null;
        Block second = null;
        for (Block candidate : candidates) {
            if (!canPlaceSingle(candidate)) {
                continue;
            }
            if (!needTwo) {
                first = candidate;
                break;
            }
            Block pair = findPair(candidate);
            if (pair != null) {
                first = candidate;
                second = pair;
                break;
            }
        }
        if (first == null) {
            for (Block candidate : candidates) {
                if (canPlaceSingle(candidate)) {
                    first = candidate;
                    break;
                }
            }
        }
        return new Block[] {first, second};
    }

    private List<Block> candidates(Location death) {
        World world = death.getWorld();
        int x = death.getBlockX();
        int z = death.getBlockZ();
        int y = death.getBlockY();
        if (y < world.getMinHeight()) {
            y = Math.min(world.getMaxHeight() - 1, world.getHighestBlockYAt(x, z) + 1);
        }
        y = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, y));
        List<Block> blocks = new ArrayList<>();
        for (int radius = 0; radius <= SEARCH_RADIUS; radius++) {
            for (int dy = -2; dy <= 3; dy++) {
                int by = y + dy;
                if (by < world.getMinHeight() || by >= world.getMaxHeight()) {
                    continue;
                }
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (radius != 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        blocks.add(world.getBlockAt(x + dx, by, z + dz));
                    }
                }
            }
        }
        return blocks;
    }

    private boolean canPlaceSingle(Block block) {
        if (!canReplace(block)) {
            return false;
        }
        for (BlockFace face : HORIZONTAL) {
            if (isChestBlock(block.getRelative(face))) {
                return false;
            }
        }
        return true;
    }

    private Block findPair(Block first) {
        for (BlockFace face : HORIZONTAL) {
            Block beside = first.getRelative(face);
            if (canPlaceBeside(first, beside)) {
                return beside;
            }
        }
        return null;
    }

    private boolean canPlaceBeside(Block first, Block beside) {
        if (!canReplace(beside)) {
            return false;
        }
        for (BlockFace face : HORIZONTAL) {
            Block neighbor = beside.getRelative(face);
            if (sameBlock(neighbor, first)) {
                continue;
            }
            if (isChestBlock(neighbor)) {
                return false;
            }
        }
        return true;
    }

    private boolean canReplace(Block block) {
        if (!block.isReplaceable()) {
            return false;
        }
        Material type = block.getType();
        return type != Material.LAVA && type != Material.FIRE && type != Material.SOUL_FIRE;
    }

    private boolean isChestBlock(Block block) {
        Material type = block.getType();
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }

    private void tag(Block block, Component name) {
        block.setType(Material.CHEST);
        if (!(block.getState() instanceof Chest chest)) {
            return;
        }
        chest.getPersistentDataContainer().set(deathChestKey, PersistentDataType.BOOLEAN, true);
        chest.customName(name);
        chest.update();
    }

    private List<ItemStack> store(Block[] placed, List<ItemStack> packed) {
        List<ItemStack> remaining = put(inventoryOf(placed[0]), packed);
        if (!remaining.isEmpty() && placed[1] != null) {
            remaining = put(inventoryOf(placed[1]), remaining);
        }
        return remaining;
    }

    private Inventory inventoryOf(Block block) {
        if (block != null && block.getState() instanceof Chest chest) {
            return chest.getBlockInventory();
        }
        return null;
    }

    private List<ItemStack> put(Inventory inventory, List<ItemStack> items) {
        if (inventory == null) {
            return new ArrayList<>(items);
        }
        List<ItemStack> leftover = new ArrayList<>();
        for (ItemStack stack : items) {
            leftover.addAll(inventory.addItem(stack).values());
        }
        return leftover;
    }

    private boolean involvesDeathChest(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        return !deathBlocks(inventory).isEmpty();
    }

    private List<Block> deathBlocks(Inventory inventory) {
        List<Block> blocks = new ArrayList<>();
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Chest chest) {
            addIfDeath(blocks, chest.getBlock());
        } else if (holder instanceof DoubleChest doubleChest) {
            addHolder(blocks, doubleChest.getLeftSide());
            addHolder(blocks, doubleChest.getRightSide());
        }
        return blocks;
    }

    private void addHolder(List<Block> blocks, InventoryHolder holder) {
        if (holder instanceof Chest chest) {
            addIfDeath(blocks, chest.getBlock());
        }
    }

    private void addIfDeath(List<Block> blocks, Block block) {
        if (isDeathChest(block)) {
            blocks.add(block);
        }
    }

    private boolean isDeathChest(Block block) {
        if (block.getType() != Material.CHEST) {
            return false;
        }
        if (!(block.getState() instanceof Chest chest)) {
            return false;
        }
        Byte mark = chest.getPersistentDataContainer().get(deathChestKey, PersistentDataType.BYTE);
        if (mark != null) {
            return mark != 0;
        }
        return chest.getPersistentDataContainer().has(deathChestKey, PersistentDataType.BOOLEAN);
    }

    private boolean isEmpty(Inventory inventory) {
        for (ItemStack stack : inventory.getContents()) {
            if (isPresent(stack)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPresent(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && stack.getAmount() > 0;
    }

    private boolean sameBlock(Block left, Block right) {
        return left.getWorld().equals(right.getWorld())
                && left.getX() == right.getX()
                && left.getY() == right.getY()
                && left.getZ() == right.getZ();
    }

    static List<ItemStack> pack(List<ItemStack> drops) {
        List<ItemStack> packed = new ArrayList<>();
        for (ItemStack incoming : drops) {
            if (incoming == null || incoming.getType().isAir() || incoming.getAmount() <= 0) {
                continue;
            }
            int remaining = incoming.getAmount();
            for (ItemStack existing : packed) {
                if (remaining <= 0) {
                    break;
                }
                if (!existing.isSimilar(incoming)) {
                    continue;
                }
                int space = existing.getMaxStackSize() - existing.getAmount();
                if (space <= 0) {
                    continue;
                }
                int move = Math.min(space, remaining);
                existing.setAmount(existing.getAmount() + move);
                remaining -= move;
            }
            while (remaining > 0) {
                ItemStack created = incoming.clone();
                int amount = Math.min(remaining, created.getMaxStackSize());
                created.setAmount(amount);
                packed.add(created);
                remaining -= amount;
            }
        }
        return packed;
    }
}
