package es.elevatorplus;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ElevatorPlugin extends JavaPlugin implements Listener, TabExecutor {

    public NamespacedKey key;
    private final Map<String, Elevator> elevators = new LinkedHashMap<>();
    private double maxSpeed = 0.35, accel = 0.015;
    private int holdTicks = 100;
    private boolean useModels = true;

    public double maxSpeed() { return maxSpeed; }
    public double accel() { return accel; }
    public int holdTicks() { return holdTicks; }
    public boolean useModels() { return useModels; }

    // =====================================================================
    // Ciclo de vida
    // =====================================================================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        maxSpeed = getConfig().getDouble("max-speed", 0.35);
        accel = getConfig().getDouble("acceleration", 0.015);
        holdTicks = (int) (getConfig().getDouble("hold-seconds", 5) * 20);
        useModels = getConfig().getBoolean("use-resource-pack", true);
        key = new NamespacedKey(this, "elevator");

        load();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("elevator");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        // los mundos pueden no estar listos en onEnable: spawneamos en el primer tick
        getServer().getScheduler().runTask(this, () -> elevators.values().forEach(Elevator::spawn));
        getServer().getScheduler().runTaskTimer(this, () -> elevators.values().forEach(Elevator::tick), 1L, 1L);
    }

    @Override
    public void onDisable() {
        elevators.values().forEach(Elevator::despawn);
        save();
    }

    // =====================================================================
    // Persistencia
    // =====================================================================

    private File dataFile() {
        return new File(getDataFolder(), "elevators.yml");
    }

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile());
        ConfigurationSection sec = y.getConfigurationSection("elevators");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            BlockFace face;
            try {
                face = BlockFace.valueOf(s.getString("facing", "NORTH"));
            } catch (IllegalArgumentException ex) {
                face = BlockFace.NORTH;
            }
            Elevator e = new Elevator(this, s.getString("name", id), s.getString("world", "world"),
                    s.getInt("x"), s.getInt("z"), s.getInt("size", 1), face);
            List<Integer> ys = s.getIntegerList("floor-y");
            List<String> ls = s.getStringList("floor-label");
            for (int i = 0; i < ys.size(); i++) {
                e.addFloor(ys.get(i), i < ls.size() ? ls.get(i) : String.valueOf(i + 1));
            }
            e.currentY = s.getInt("current", ys.isEmpty() ? 0 : ys.get(0));
            for (String b : s.getStringList("buttons")) {
                int idx = b.lastIndexOf(',');
                if (idx < 0) continue;
                try {
                    e.buttons.put(b.substring(0, idx), Integer.parseInt(b.substring(idx + 1)));
                } catch (NumberFormatException ignored) { }
            }
            elevators.put(id, e);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var en : elevators.entrySet()) {
            Elevator e = en.getValue();
            String p = "elevators." + en.getKey() + ".";
            y.set(p + "name", e.name);
            y.set(p + "world", e.worldName);
            y.set(p + "x", e.ox);
            y.set(p + "z", e.oz);
            y.set(p + "size", e.size);
            y.set(p + "facing", e.facing.name());
            y.set(p + "current", e.currentY());
            List<Integer> ys = new ArrayList<>();
            List<String> ls = new ArrayList<>();
            for (Elevator.Floor f : e.floors) {
                ys.add(f.y());
                ls.add(f.label());
            }
            y.set(p + "floor-y", ys);
            y.set(p + "floor-label", ls);
            List<String> bs = new ArrayList<>();
            e.buttons.forEach((k, v) -> bs.add(k + "," + v));
            y.set(p + "buttons", bs);
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile());
        } catch (IOException ex) {
            getLogger().severe("No se pudo guardar elevators.yml: " + ex.getMessage());
        }
    }

    private static String blockKey(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    // =====================================================================
    // Mensajes
    // =====================================================================

    private static void ok(CommandSender s, String t) {
        s.sendMessage(Component.text("[Ascensor] ", NamedTextColor.GOLD).append(Component.text(t, NamedTextColor.GREEN)));
    }

    private static void err(CommandSender s, String t) {
        s.sendMessage(Component.text("[Ascensor] ", NamedTextColor.GOLD).append(Component.text(t, NamedTextColor.RED)));
    }

    // =====================================================================
    // Comandos
    // =====================================================================

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            help(s);
            return true;
        }
        String sub = a[0].toLowerCase();
        if (sub.equals("call")) {
            if (!s.hasPermission("elevator.use")) { err(s, "Sin permiso."); return true; }
            if (a.length < 3) { err(s, "Uso: /elevator call <nombre> <planta>"); return true; }
            Elevator e = elevators.get(a[1].toLowerCase());
            if (e == null) { err(s, "No existe ese ascensor."); return true; }
            Elevator.Floor f = e.floorByLabel(a[2]);
            if (f == null) { err(s, "No existe esa planta."); return true; }
            e.request(f.y());
            ok(s, "Ascensor llamado a la planta " + f.label() + ".");
            return true;
        }
        if (!s.hasPermission("elevator.admin")) { err(s, "Sin permiso."); return true; }
        if (sub.equals("list")) {
            if (elevators.isEmpty()) { ok(s, "No hay ascensores."); return true; }
            for (Elevator e : elevators.values()) {
                StringBuilder sb = new StringBuilder();
                for (Elevator.Floor f : e.floors) sb.append(f.label()).append("(y=").append(f.y()).append(") ");
                ok(s, e.name + " [" + e.size + "x" + e.size + ", puertas " + e.facing + "] plantas: " + sb);
            }
            return true;
        }
        if (!(s instanceof Player p)) { err(s, "Solo desde el juego."); return true; }

        switch (sub) {
            case "create" -> create(p, a);
            case "addfloor" -> addFloor(p, a);
            case "button" -> button(p, a);
            case "removebutton" -> removeButton(p);
            case "remove" -> {
                if (a.length < 2) { err(p, "Uso: /elevator remove <nombre>"); return true; }
                Elevator e = elevators.remove(a[1].toLowerCase());
                if (e == null) { err(p, "No existe ese ascensor."); return true; }
                e.despawn();
                save();
                ok(p, "Ascensor eliminado.");
            }
            default -> help(p);
        }
        return true;
    }

    private void help(CommandSender s) {
        ok(s, "/elevator create <nombre> <1x1|2x2>  -> en la esquina de menor X/Z del hueco, mirando hacia las puertas");
        ok(s, "/elevator addfloor <nombre> [etiqueta]  -> anade una planta a tu altura actual");
        ok(s, "/elevator button <nombre> [etiqueta]  -> convierte el bloque que miras en boton de llamada");
        ok(s, "/elevator removebutton  -> quita el boton que miras");
        ok(s, "/elevator call <nombre> <etiqueta> | list | remove <nombre>");
    }

    private void create(Player p, String[] a) {
        if (a.length < 3) { err(p, "Uso: /elevator create <nombre> <1x1|2x2>"); return; }
        String id = a[1].toLowerCase();
        if (elevators.containsKey(id)) { err(p, "Ya existe un ascensor con ese nombre."); return; }
        int size = a[2].startsWith("2") ? 2 : 1;
        var loc = p.getLocation();
        BlockFace face = p.getFacing();
        if (face != BlockFace.NORTH && face != BlockFace.SOUTH && face != BlockFace.EAST && face != BlockFace.WEST) {
            face = BlockFace.NORTH;
        }
        Elevator e = new Elevator(this, a[1], p.getWorld().getName(), loc.getBlockX(), loc.getBlockZ(), size, face);
        e.addFloor(loc.getBlockY(), "1");
        e.currentY = loc.getBlockY();
        elevators.put(id, e);
        e.spawn();
        save();
        ok(p, "Ascensor " + size + "x" + size + " creado con puertas hacia " + face
                + ". Sube/baja y usa /elevator addfloor " + a[1] + " en cada planta.");
    }

    private void addFloor(Player p, String[] a) {
        if (a.length < 2) { err(p, "Uso: /elevator addfloor <nombre> [etiqueta]"); return; }
        Elevator e = elevators.get(a[1].toLowerCase());
        if (e == null) { err(p, "No existe ese ascensor."); return; }
        int y = p.getLocation().getBlockY();
        if (e.hasFloor(y)) { err(p, "Ya hay una planta a esa altura."); return; }
        String label = a.length > 2 ? a[2] : String.valueOf(e.floors.size() + 1);
        if (e.floorByLabel(label) != null) { err(p, "Ya existe una planta con esa etiqueta."); return; }
        e.addFloor(y, label);
        e.refreshText();
        save();
        ok(p, "Planta '" + label + "' anadida en y=" + y + ".");
        var l = p.getLocation();
        if (l.getBlockX() < e.ox || l.getBlockX() >= e.ox + e.size || l.getBlockZ() < e.oz || l.getBlockZ() >= e.oz + e.size) {
            err(p, "Aviso: no estas dentro del hueco del ascensor (solo cuenta la altura).");
        }
    }

    private void button(Player p, String[] a) {
        if (a.length < 2) { err(p, "Uso: /elevator button <nombre> [etiqueta]"); return; }
        Elevator e = elevators.get(a[1].toLowerCase());
        if (e == null) { err(p, "No existe ese ascensor."); return; }
        Block b = p.getTargetBlockExact(6);
        if (b == null) { err(p, "Mira a un bloque (max 6 bloques)."); return; }
        Elevator.Floor f = a.length > 2 ? e.floorByLabel(a[2]) : e.floorFor(b.getY());
        if (f == null) { err(p, "No existe esa planta."); return; }
        e.buttons.put(blockKey(b), f.y());
        save();
        ok(p, "Boton de llamada registrado para la planta " + f.label() + ".");
    }

    private void removeButton(Player p) {
        Block b = p.getTargetBlockExact(6);
        if (b == null) { err(p, "Mira a un bloque."); return; }
        String k = blockKey(b);
        boolean removed = false;
        for (Elevator e : elevators.values()) if (e.buttons.remove(k) != null) removed = true;
        if (removed) { save(); ok(p, "Boton eliminado."); }
        else err(p, "Ese bloque no es un boton.");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("create", "addfloor", "button", "removebutton", "call", "list", "remove", "help"));
        } else if (a.length == 2 && !a[0].equalsIgnoreCase("create") && !a[0].equalsIgnoreCase("list")
                && !a[0].equalsIgnoreCase("removebutton") && !a[0].equalsIgnoreCase("help")) {
            for (Elevator e : elevators.values()) out.add(e.name);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("create")) {
            out.addAll(List.of("1x1", "2x2"));
        } else if (a.length == 3 && (a[0].equalsIgnoreCase("call") || a[0].equalsIgnoreCase("button"))) {
            Elevator e = elevators.get(a[1].toLowerCase());
            if (e != null) for (Elevator.Floor f : e.floors) out.add(f.label());
        }
        String pref = a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(pref));
        return out;
    }

    // =====================================================================
    // Eventos
    // =====================================================================

    /** Botones de llamada en los pisos. */
    @EventHandler
    public void onInteract(PlayerInteractEvent ev) {
        if (ev.getHand() != EquipmentSlot.HAND || ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = ev.getClickedBlock();
        if (b == null) return;
        String k = blockKey(b);
        for (Elevator e : elevators.values()) {
            Integer y = e.buttons.get(k);
            if (y == null) continue;
            ev.setCancelled(true);
            Player p = ev.getPlayer();
            if (!p.hasPermission("elevator.use")) return;
            b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), "minecraft:block.stone_button.click_on",
                    SoundCategory.BLOCKS, 0.8f, 1.2f);
            if (e.currentY() == y && e.state() == Elevator.State.IDLE_OPEN) {
                p.sendActionBar(Component.text("El ascensor ya esta aqui", NamedTextColor.YELLOW));
            } else {
                e.request(y);
                p.sendActionBar(Component.text("Ascensor llamado...", NamedTextColor.GREEN));
            }
            return;
        }
    }

    /** Panel de botones dentro de la cabina. */
    @EventHandler
    public void onEntity(PlayerInteractEntityEvent ev) {
        if (ev.getHand() != EquipmentSlot.HAND) return;
        if (!(ev.getRightClicked() instanceof Interaction in)) return;
        String id = in.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) return;
        ev.setCancelled(true);
        Elevator e = elevators.get(id);
        Player p = ev.getPlayer();
        if (e == null || !p.hasPermission("elevator.use")) return;
        openMenu(p, e);
    }

    private void openMenu(Player p, Elevator e) {
        Menu m = new Menu(e);
        List<Elevator.Floor> fl = new ArrayList<>(e.floors);
        Collections.reverse(fl); // planta mas alta arriba
        int rows = Math.min(6, Math.max(1, (fl.size() + 8) / 9));
        m.inv = Bukkit.createInventory(m, rows * 9, Component.text("Ascensor " + e.name));
        for (int i = 0; i < Math.min(fl.size(), rows * 9); i++) {
            Elevator.Floor f = fl.get(i);
            boolean here = f.y() == e.currentY();
            ItemStack it = new ItemStack(here ? Material.LIME_CONCRETE : Material.WHITE_CONCRETE);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(Component.text("Planta " + f.label(), here ? NamedTextColor.GREEN : NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(here ? "Estas aqui" : "Click para ir (y=" + f.y() + ")", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            it.setItemMeta(meta);
            m.inv.setItem(i, it);
            m.slotY.add(f.y());
        }
        p.openInventory(m.inv);
        p.playSound(p.getLocation(), "minecraft:block.stone_button.click_on", SoundCategory.BLOCKS, 0.6f, 1.4f);
    }

    @EventHandler
    public void onClick(InventoryClickEvent ev) {
        if (!(ev.getInventory().getHolder() instanceof Menu m)) return;
        ev.setCancelled(true);
        if (ev.getClickedInventory() != ev.getInventory()) return;
        int slot = ev.getSlot();
        if (slot < 0 || slot >= m.slotY.size()) return;
        Player p = (Player) ev.getWhoClicked();
        int y = m.slotY.get(slot);
        p.closeInventory();
        if (y == m.el.currentY() && m.el.state() == Elevator.State.IDLE_OPEN) return;
        m.el.request(y);
        p.playSound(p.getLocation(), "minecraft:block.stone_button.click_on", SoundCategory.BLOCKS, 0.8f, 1.2f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent ev) {
        if (ev.getInventory().getHolder() instanceof Menu) ev.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        for (Elevator e : elevators.values()) e.releaseRider(ev.getPlayer());
    }

    private static final class Menu implements InventoryHolder {
        final Elevator el;
        final List<Integer> slotY = new ArrayList<>();
        Inventory inv;

        Menu(Elevator el) { this.el = el; }

        @Override
        public Inventory getInventory() { return inv; }
    }
}
