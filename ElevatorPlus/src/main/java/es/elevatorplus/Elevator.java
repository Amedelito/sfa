package es.elevatorplus;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Un ascensor: hueco (1x1 o 2x2), plantas, cabina animada (BlockDisplay),
 * puertas correderas y maquina de estados.
 *
 * Convencion de coordenadas locales de la cabina:
 *   u = ancho (a lo largo de la puerta), v = profundidad (0 = lado de la puerta), y = altura.
 */
public final class Elevator {

    public record Floor(int y, String label) {}

    private record Part(Entity e, double x, double y, double z) {}

    private record Rider(boolean allowFlight, double offset) {}

    private static final class Door {
        Display d;
        Transformation closed, open;
    }

    public enum State { IDLE_CLOSED, IDLE_OPEN, CLOSING, OPENING, MOVING }

    // ---- Materiales (cambialos a tu gusto) ----
    private static final Material M_FLOOR = Material.SMOOTH_QUARTZ;
    private static final Material M_CARPET = Material.BLACK_CONCRETE;
    private static final Material M_PILLAR = Material.POLISHED_BLACKSTONE;
    private static final Material M_GLASS = Material.GLASS;
    private static final Material M_RAIL = Material.GOLD_BLOCK;
    private static final Material M_CEILING = Material.SMOOTH_QUARTZ;
    private static final Material M_LAMP = Material.SEA_LANTERN;
    private static final Material M_DOOR = Material.IRON_BLOCK;
    private static final Material M_PANEL = Material.POLISHED_BLACKSTONE_BRICKS;

    // CustomModelData del resource pack (base: iron_ingot)
    private static final int MODEL_1X1 = 9101, MODEL_2X2 = 9102, MODEL_DOOR = 9103;

    private static final int DOOR_TICKS = 18;
    private static final float H = 2.6f; // altura interior de la cabina

    private final ElevatorPlugin plugin;
    public final String name;
    public final String worldName;
    public final int ox, oz, size;
    public final BlockFace facing;
    public final List<Floor> floors = new ArrayList<>();
    /** "mundo;x,y,z" -> y de la planta */
    public final Map<String, Integer> buttons = new HashMap<>();
    public int currentY;

    // ---- runtime ----
    private State state = State.IDLE_CLOSED;
    private boolean spawned;
    private double cabinY;
    private int targetY;
    private int timer;
    private int tickCount;
    private double speed;
    private String lastText = "";
    private final Deque<Integer> queue = new ArrayDeque<>();
    private final List<Part> parts = new ArrayList<>();
    private final List<Door> doors = new ArrayList<>();
    private final List<TextDisplay> labels = new ArrayList<>();
    private final List<Location> barriers = new ArrayList<>();
    private final Map<UUID, Rider> riders = new HashMap<>();

    public Elevator(ElevatorPlugin plugin, String name, String worldName, int ox, int oz, int size, BlockFace facing) {
        this.plugin = plugin;
        this.name = name;
        this.worldName = worldName;
        this.ox = ox;
        this.oz = oz;
        this.size = size;
        this.facing = facing;
    }

    // =====================================================================
    // Plantas
    // =====================================================================

    public void addFloor(int y, String label) {
        floors.add(new Floor(y, label));
        floors.sort(Comparator.comparingInt(Floor::y));
    }

    public boolean hasFloor(int y) {
        return floors.stream().anyMatch(f -> f.y() == y);
    }

    public Floor floorByLabel(String label) {
        for (Floor f : floors) if (f.label().equalsIgnoreCase(label)) return f;
        return null;
    }

    /** Planta mas alta cuya y sea <= y; si no hay, la mas baja. */
    public Floor floorFor(int y) {
        Floor best = null;
        for (Floor f : floors) if (f.y() <= y) best = f;
        return best != null ? best : (floors.isEmpty() ? null : floors.get(0));
    }

    private Floor nearest(double y) {
        Floor best = floors.get(0);
        for (Floor f : floors) if (Math.abs(f.y() - y) < Math.abs(best.y() - y)) best = f;
        return best;
    }

    public int currentY() { return currentY; }

    public State state() { return state; }

    // =====================================================================
    // Geometria
    // =====================================================================

    private World world() { return Bukkit.getWorld(worldName); }

    /** local (u,v) -> mundo (x,z). La puerta esta en v = 0. */
    private double[] map(double u, double v) {
        double s = size;
        return switch (facing) {
            case NORTH -> new double[]{ox + u, oz + v};
            case SOUTH -> new double[]{ox + s - u, oz + s - v};
            case EAST -> new double[]{ox + s - v, oz + u};
            default -> new double[]{ox + v, oz + s - u}; // WEST
        };
    }

    /** Caja local -> {minX, minZ, sizeX, sizeZ} en mundo. */
    private double[] xz(double u, double v, double du, double dv) {
        double[] a = map(u, v), b = map(u + du, v + dv);
        return new double[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1])};
    }

    private BlockDisplay box(World w, Material m, double u, double v, double y, double du, double dv, double dy, boolean glow) {
        return spawnBox(w, m, xz(u, v, du, dv), y, dy, glow);
    }

    private BlockDisplay spawnBox(World w, Material m, double[] r, double y, double h, boolean glow) {
        Location loc = new Location(w, r[0], cabinY + y, r[1]);
        BlockDisplay d = w.spawn(loc, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f((float) r[2], (float) h, (float) r[3]), new AxisAngle4f()));
            e.setBrightness(new Display.Brightness(glow ? 15 : 12, 15));
            e.setPersistent(false);
            e.setTeleportDuration(1);
            e.setViewRange(1.5f);
            e.getPersistentDataContainer().set(plugin.key, PersistentDataType.STRING, name.toLowerCase());
        });
        parts.add(new Part(d, r[0], y, r[1]));
        return d;
    }

    private void addDoor(World w, double uClosed, double duClosed, double uOpen, double duOpen) {
        double v = 0.03, dv = 0.06;
        double[] c = xz(uClosed, v, duClosed, dv);
        double[] o = xz(uOpen, v, duOpen, dv);
        Transformation closed = new Transformation(new Vector3f(), new AxisAngle4f(),
                new Vector3f((float) c[2], H, (float) c[3]), new AxisAngle4f());
        Door door = new Door();
        door.closed = closed;
        if (plugin.useModels()) {
            // modelo = cubo unidad centrado en la entidad -> posicion = centro de la hoja
            double ccx = c[0] + c[2] / 2, ccz = c[1] + c[3] / 2;
            Location loc = new Location(w, ccx, cabinY + H / 2.0, ccz);
            ItemStack it = modelItem(MODEL_DOOR);
            ItemDisplay d = w.spawn(loc, ItemDisplay.class, e -> {
                e.setItemStack(it);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setTransformation(closed);
                e.setBrightness(new Display.Brightness(13, 15));
                e.setPersistent(false);
                e.setTeleportDuration(1);
                e.setViewRange(1.5f);
                e.getPersistentDataContainer().set(plugin.key, PersistentDataType.STRING, name.toLowerCase());
            });
            parts.add(new Part(d, ccx, H / 2.0, ccz));
            door.d = d;
            door.open = new Transformation(
                    new Vector3f((float) ((o[0] + o[2] / 2) - ccx), 0, (float) ((o[1] + o[3] / 2) - ccz)),
                    new AxisAngle4f(), new Vector3f((float) o[2], H, (float) o[3]), new AxisAngle4f());
        } else {
            door.d = spawnBox(w, M_DOOR, c, 0, H, false);
            door.open = new Transformation(
                    new Vector3f((float) (o[0] - c[0]), 0, (float) (o[1] - c[1])), new AxisAngle4f(),
                    new Vector3f((float) o[2], H, (float) o[3]), new AxisAngle4f());
        }
        doors.add(door);
    }

    private ItemStack modelItem(int cmd) {
        ItemStack it = new ItemStack(Material.IRON_INGOT);
        ItemMeta m = it.getItemMeta();
        m.setCustomModelData(cmd);
        it.setItemMeta(m);
        return it;
    }

    /** Cabina completa con el modelo del resource pack (1 sola entidad). */
    private void buildModelBody(World w) {
        double[] c = map(size / 2.0, size / 2.0);
        // en el modelo el suelo queda 1.4 bloques por debajo del centro
        double y = 1.4;
        Location loc = new Location(w, c[0], cabinY + y, c[1]);
        float yaw = switch (facing) {
            case WEST -> 90f;
            case SOUTH -> 180f;
            case EAST -> -90f;
            default -> 0f; // NORTH: puerta hacia -Z, igual que el modelo
        };
        ItemStack it = modelItem(size == 2 ? MODEL_2X2 : MODEL_1X1);
        ItemDisplay d = w.spawn(loc, ItemDisplay.class, e -> {
            e.setItemStack(it);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setTransformation(new Transformation(new Vector3f(),
                    new AxisAngle4f((float) Math.toRadians(yaw), 0, 1, 0),
                    new Vector3f(1, 1, 1), new AxisAngle4f()));
            e.setBrightness(new Display.Brightness(13, 15));
            e.setPersistent(false);
            e.setTeleportDuration(1);
            e.setViewRange(2f);
            e.getPersistentDataContainer().set(plugin.key, PersistentDataType.STRING, name.toLowerCase());
        });
        parts.add(new Part(d, c[0], y, c[1]));
    }

    private TextDisplay label(World w, double u, double v, double y, float scale) {
        double[] p = map(u, v);
        Location loc = new Location(w, p[0], cabinY + y, p[1]);
        TextDisplay t = w.spawn(loc, TextDisplay.class, e -> {
            e.setBillboard(Display.Billboard.CENTER);
            e.setBackgroundColor(Color.fromARGB(170, 0, 0, 0));
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
            e.setPersistent(false);
            e.setTeleportDuration(1);
            e.setViewRange(1f);
            e.text(Component.text("-"));
        });
        parts.add(new Part(t, p[0], y, p[1]));
        labels.add(t);
        return t;
    }

    // =====================================================================
    // Spawn / despawn
    // =====================================================================

    public void spawn() {
        World w = world();
        if (w == null || floors.isEmpty()) return;
        despawn();

        boolean ok = false;
        for (Floor f : floors) if (f.y() == currentY) ok = true;
        if (!ok) currentY = floors.get(0).y();
        cabinY = currentY;

        for (int cx : new int[]{ox >> 4, (ox + size - 1) >> 4})
            for (int cz : new int[]{oz >> 4, (oz + size - 1) >> 4})
                w.addPluginChunkTicket(cx, cz, plugin);

        double s = size, p = 0.12;

        if (plugin.useModels()) buildModelBody(w);
        else buildVanillaBody(w, s, p);

        double[] pc = map(s / 2, s - 0.2);
        Location il = new Location(w, pc[0], cabinY + 0.9, pc[1]);
        Interaction in = w.spawn(il, Interaction.class, e -> {
            e.setInteractionWidth(0.5f);
            e.setInteractionHeight(0.7f);
            e.setPersistent(false);
            e.getPersistentDataContainer().set(plugin.key, PersistentDataType.STRING, name.toLowerCase());
        });
        parts.add(new Part(in, pc[0], 0.9, pc[1]));

        // Puertas correderas (se esconden en los pilares al abrir)
        addDoor(w, 0, s / 2, 0, 0.1);
        addDoor(w, s / 2, s / 2, s - 0.1, 0.1);

        // Indicadores de planta (dentro y encima de la puerta, por fuera)
        label(w, s / 2, 0.25, 2.25, 0.45f);
        label(w, s / 2, -0.12, H + 0.3, 0.55f);

        spawned = true;
        state = State.IDLE_CLOSED;
        placeBarriers();
        lastText = "";
        updateText(0);
    }

    /** Cabina con bloques vanilla (sin resource pack). */
    private void buildVanillaBody(World w, double s, double p) {
        // Suelo + alfombra
        box(w, M_FLOOR, 0, 0, -0.1, s, s, 0.1, false);
        box(w, M_CARPET, 0.14, 0.14, 0.0, s - 0.28, s - 0.28, 0.02, false);

        // Pilares
        for (double[] c : new double[][]{{0, 0}, {s - p, 0}, {0, s - p}, {s - p, s - p}})
            box(w, M_PILLAR, c[0], c[1], -0.1, p, p, H + 0.1, false);

        // Paredes de cristal (fondo + laterales)
        box(w, M_GLASS, p, s - 0.06, 0, s - 2 * p, 0.06, H, false);
        box(w, M_GLASS, 0, p, 0, 0.06, s - 2 * p, H, false);
        box(w, M_GLASS, s - 0.06, p, 0, 0.06, s - 2 * p, H, false);

        // Pasamanos
        box(w, M_RAIL, p, s - 0.11, 0.75, s - 2 * p, 0.05, 0.05, false);
        box(w, M_RAIL, 0.06, p, 0.75, 0.05, s - 2 * p, 0.05, false);
        box(w, M_RAIL, s - 0.11, p, 0.75, 0.05, s - 2 * p, 0.05, false);

        // Techo + luz
        box(w, M_CEILING, 0, 0, H, s, s, 0.1, false);
        box(w, M_LAMP, 0.2, 0.2, H - 0.05, s - 0.4, s - 0.4, 0.05, true);

        // Panel de botones (fondo de la cabina)
        double pu = s / 2 - 0.2;
        box(w, M_PANEL, pu, s - 0.16, 0.9, 0.4, 0.1, 0.6, false);
        box(w, Material.LIME_CONCRETE, s / 2 - 0.04, s - 0.18, 1.35, 0.08, 0.02, 0.08, true);
        box(w, Material.YELLOW_CONCRETE, s / 2 - 0.04, s - 0.18, 1.20, 0.08, 0.02, 0.08, true);
        box(w, Material.RED_CONCRETE, s / 2 - 0.04, s - 0.18, 1.05, 0.08, 0.02, 0.08, true);
    }

    public void despawn() {
        releaseAll(true);
        for (Part p : parts) {
            if (p.e().isValid()) p.e().remove();
        }
        parts.clear();
        doors.clear();
        labels.clear();
        removeBarriers();
        World w = world();
        if (w != null) {
            for (int cx : new int[]{ox >> 4, (ox + size - 1) >> 4})
                for (int cz : new int[]{oz >> 4, (oz + size - 1) >> 4})
                    w.removePluginChunkTicket(cx, cz, plugin);
        }
        spawned = false;
        queue.clear();
        state = State.IDLE_CLOSED;
    }

    // =====================================================================
    // Barreras (suelo invisible mientras la cabina esta parada)
    // =====================================================================

    private void placeBarriers() {
        World w = world();
        if (w == null) return;
        int y = (int) Math.round(cabinY) - 1;
        for (int dx = 0; dx < size; dx++)
            for (int dz = 0; dz < size; dz++) {
                Block b = w.getBlockAt(ox + dx, y, oz + dz);
                if (b.getType().isAir()) {
                    b.setType(Material.BARRIER, false);
                    barriers.add(b.getLocation());
                }
            }
    }

    private void removeBarriers() {
        for (Location l : barriers) {
            Block b = l.getBlock();
            if (b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
        }
        barriers.clear();
    }

    // =====================================================================
    // API publica
    // =====================================================================

    public boolean request(int y) {
        if (!spawned || !hasFloor(y)) return false;
        if (state == State.MOVING && targetY == y) return true;
        if (!queue.contains(y)) queue.add(y);
        return true;
    }

    public boolean isIn(Player p) {
        return inCabin(p);
    }

    public void releaseRider(Player p) {
        Rider r = riders.remove(p.getUniqueId());
        if (r != null) restore(p, r);
    }

    // =====================================================================
    // Maquina de estados
    // =====================================================================

    public void tick() {
        if (!spawned) return;
        switch (state) {
            case IDLE_CLOSED -> {
                Integer n = queue.poll();
                if (n != null) {
                    if (n == currentY) openDoors();
                    else beginMove(n);
                }
            }
            case IDLE_OPEN -> {
                queue.removeIf(y -> y == currentY);
                if (!queue.isEmpty() || ++timer > plugin.holdTicks()) closeDoors();
            }
            case CLOSING -> {
                if (--timer <= 0) state = State.IDLE_CLOSED;
            }
            case OPENING -> {
                if (--timer <= 0) {
                    state = State.IDLE_OPEN;
                    timer = 0;
                }
            }
            case MOVING -> moveTick();
        }
    }

    private void openDoors() {
        animateDoors(true);
        doorSound("minecraft:block.iron_door.open", 1.0f, 0.8f);
        state = State.OPENING;
        timer = DOOR_TICKS;
    }

    private void closeDoors() {
        animateDoors(false);
        doorSound("minecraft:block.iron_door.close", 1.0f, 0.8f);
        state = State.CLOSING;
        timer = DOOR_TICKS;
    }

    private void animateDoors(boolean open) {
        for (Door d : doors) {
            d.d.setInterpolationDelay(0);
            d.d.setInterpolationDuration(DOOR_TICKS);
            d.d.setTransformation(open ? d.open : d.closed);
        }
    }

    private boolean inCabin(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) return false;
        Location l = p.getLocation();
        return l.getX() >= ox && l.getX() <= ox + size
                && l.getZ() >= oz && l.getZ() <= oz + size
                && l.getY() >= cabinY - 0.5 && l.getY() <= cabinY + 2.4;
    }

    private void beginMove(int ty) {
        World w = world();
        if (w == null) return;
        targetY = ty;
        speed = 0;
        tickCount = 0;
        riders.clear();
        for (Player p : w.getPlayers()) {
            if (inCabin(p)) {
                riders.put(p.getUniqueId(), new Rider(p.getAllowFlight(), Math.max(0, p.getLocation().getY() - cabinY)));
                p.setGravity(false);
                p.setAllowFlight(true);
            }
        }
        removeBarriers();
        sound("minecraft:block.piston.extend", 0.7f, 0.5f);
        sound("minecraft:block.iron_trapdoor.open", 0.8f, 0.6f);
        state = State.MOVING;
    }

    private void moveTick() {
        World w = world();
        if (w == null) return;
        double dist = targetY - cabinY;
        double remaining = Math.abs(dist);
        double dir = Math.signum(dist);
        double accel = plugin.accel();

        speed = Math.min(Math.min(plugin.maxSpeed(), speed + accel), Math.sqrt(2 * accel * remaining) + 0.02);
        double step = Math.min(speed, remaining);
        double before = cabinY;
        cabinY += dir * step;

        moveParts(w);
        carryRiders(w, dir * step);

        // clic suave al pasar por cada planta intermedia
        for (Floor f : floors) {
            if (f.y() == targetY) continue;
            if ((f.y() - before) * (f.y() - cabinY) < 0) sound("minecraft:block.note_block.hat", 0.6f, 1.6f);
        }

        updateText((int) dir);

        if (tickCount % 5 == 0) sound("minecraft:block.chain.step", 0.35f, 0.5f);
        if (tickCount % 40 == 0) sound("minecraft:block.beacon.ambient", 0.35f, 0.5f);
        tickCount++;

        if (remaining - step <= 1e-6) arrive(w);
    }

    private void moveParts(World w) {
        for (Part p : parts) {
            if (p.e().isValid()) p.e().teleport(new Location(w, p.x(), cabinY + p.y(), p.z()));
        }
    }

    private void carryRiders(World w, double dy) {
        var it = riders.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isOnline() || !p.getWorld().equals(w)) {
                it.remove();
                continue;
            }
            Location l = p.getLocation();
            if (Math.abs(l.getY() - (cabinY + en.getValue().offset())) > 5.0) {
                restore(p, en.getValue());
                it.remove();
                continue;
            }
            double nx = Math.max(ox + 0.25, Math.min(ox + size - 0.25, l.getX()));
            double nz = Math.max(oz + 0.25, Math.min(oz + size - 0.25, l.getZ()));
            if (nx != l.getX() || nz != l.getZ()) {
                Location t = l.clone();
                t.setX(nx);
                t.setZ(nz);
                p.teleport(t);
            }
            p.setVelocity(new Vector(0, dy, 0));
            p.setFallDistance(0f);
        }
    }

    private void arrive(World w) {
        cabinY = targetY;
        currentY = targetY;
        moveParts(w);
        placeBarriers();

        for (var en : riders.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isOnline()) continue;
            Location l = p.getLocation();
            l.setY(cabinY + Math.min(0.3, en.getValue().offset()));
            p.teleport(l);
            p.setVelocity(new Vector(0, 0, 0));
            p.setFallDistance(0f);
            restore(p, en.getValue());
        }
        riders.clear();

        lastText = "";
        updateText(0);

        sound("minecraft:block.piston.contract", 0.7f, 0.6f);
        sound("minecraft:block.note_block.chime", 1.2f, 1.5f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> sound("minecraft:block.note_block.chime", 1.2f, 1.2f), 5L);

        openDoors();
    }

    private void restore(Player p, Rider r) {
        p.setGravity(true);
        p.setAllowFlight(r.allowFlight());
    }

    private void releaseAll(boolean snapToTarget) {
        World w = world();
        boolean moving = state == State.MOVING;
        for (var en : riders.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isOnline()) continue;
            if (snapToTarget && moving && w != null) {
                Location l = p.getLocation();
                l.setY(targetY + Math.min(0.3, en.getValue().offset()));
                p.teleport(l);
                p.setVelocity(new Vector(0, 0, 0));
                p.setFallDistance(0f);
            }
            restore(p, en.getValue());
        }
        riders.clear();
        if (snapToTarget && moving) {
            currentY = targetY;
            cabinY = targetY;
        }
    }

    // =====================================================================
    // Texto y sonido
    // =====================================================================

    public void refreshText() {
        if (!spawned) return;
        lastText = "";
        updateText(0);
    }

    private void updateText(int dir) {
        if (floors.isEmpty() || labels.isEmpty()) return;
        Floor f = nearest(cabinY);
        String key = dir + f.label();
        if (key.equals(lastText)) return;
        lastText = key;
        Component c = Component.text(f.label(), NamedTextColor.WHITE, TextDecoration.BOLD);
        if (dir > 0) c = Component.text("\u25B2 ", NamedTextColor.GREEN).append(c);
        else if (dir < 0) c = Component.text("\u25BC ", NamedTextColor.RED).append(c);
        for (TextDisplay t : labels) t.text(c);
    }

    /** Sonido 3D en el centro de la cabina. */
    private void sound(String s, float vol, float pitch) {
        World w = world();
        if (w == null) return;
        double[] c = map(size / 2.0, size / 2.0);
        w.playSound(new Location(w, c[0], cabinY + 1.2, c[1]), s, SoundCategory.BLOCKS, vol, pitch);
    }

    /** Sonido 3D en la puerta. */
    private void doorSound(String s, float vol, float pitch) {
        World w = world();
        if (w == null) return;
        double[] c = map(size / 2.0, 0);
        w.playSound(new Location(w, c[0], cabinY + 1.2, c[1]), s, SoundCategory.BLOCKS, vol, pitch);
    }
}
