package com.chronowarden;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * δ Хроно Варден - сжатая первая версия. 4 приёма:
 *   Ball (0)   - тап/удержание решает уровень: 1 просто взрыв, 2 притягивает и взрывается, 3 маленький таймстоп
 *   Anchor (1) - 1-й нажим ставит точку, 2-й откатывает тебя туда, лечит и бьёт врагов рядом с точкой.
 *                Если умрёшь, пока точка стоит - возрождает там с малым HP, но с бешеным уроном на время.
 *   Halt (2)   - маленькая зона перед лицом останавливает снаряды и мобов на несколько секунд
 *   TS (3)     - большие часы вбиваются в землю, потом полный тайм-стоп: все вокруг замирают, ты - нет
 *
 * Клавиши по умолчанию: Ball = K, Anchor = L, Halt = P, TS = O (см. ChronoClient).
 */
public final class ChronoManager {
    private ChronoManager() {}

    // ================== НАСТРОЙКИ ==================
    static final int[] COOLDOWN = {80, 20, 300, 1200}; // Ball, Anchor(и то и то), Halt, TS - в тиках
    static final int BALL_TAP_TICKS = 6;     // < 6 тиков (0.3с) = уровень 1
    static final int BALL_HOLD_TICKS = 20;   // < 20 тиков (1с) = уровень 2, иначе уровень 3

    static final float BALL1_DAMAGE = 12.0f;
    static final float BALL1_RADIUS = 4.0f;
    static final float BALL2_DAMAGE = 10.0f;
    static final float BALL2_PULL_RADIUS = 8.0f;
    static final float BALL2_BLAST_RADIUS = 4.5f;
    static final float BALL3_FIELD_RADIUS = 4.0f;
    static final int BALL3_FREEZE_TICKS = 60; // 3 с

    static final float ANCHOR_BACKLASH_FRACTION = 0.5f; // доля накопленного урона, которая при рывке бьёт врагов у точки
    static final float ANCHOR_BACKLASH_RADIUS = 5.0f;
    static final float ANCHOR_DEATH_HP_FRACTION = 0.2f;  // возрождение: доля от максимума HP
    static final int ANCHOR_DEATH_BUFF_TICKS = 200;      // 10 с бешеного урона

    static final double HALT_RANGE = 3.0;     // на каком расстоянии перед лицом
    static final double HALT_HALF_SIZE = 1.4; // размер зоны
    static final int HALT_DURATION = 100;     // 5 с

    static final double TS_RADIUS = 20.0;
    static final int TS_WINDUP = 20;   // 1 с - часы вбиваются в землю
    static final int TS_DURATION = 100; // 5 с сам тайм-стоп
    // =================================================

    private static final DustParticleOptions GOLD = new DustParticleOptions(new Vector3f(1.0f, 0.82f, 0.2f), 1.3f);
    private static final DustParticleOptions GOLD_BIG = new DustParticleOptions(new Vector3f(1.0f, 0.82f, 0.2f), 1.9f);
    private static final DustParticleOptions BLUE = new DustParticleOptions(new Vector3f(0.3f, 0.6f, 1.0f), 1.5f);
    private static final DustParticleOptions DARK = new DustParticleOptions(new Vector3f(0.05f, 0.05f, 0.08f), 1.2f);

    private static final class PState {
        final long[] cd = new long[4];
        final boolean[] held = new boolean[4];
        long pressTick;
        // anchor
        boolean anchorSet;
        ServerLevel anchorLevel;
        Vec3 anchorPos;
        float damageSinceAnchor;
        long berserkUntil;
        // timestop
        boolean tsActive;
        long tsEndTick;
        final Map<UUID, Vec3> tsFrozen = new HashMap<>();
    }

    private static final class Ball {
        final UUID owner; final ServerLevel level; Vec3 pos; final Vec3 vel; final int lvl; int age;
        Ball(UUID owner, ServerLevel level, Vec3 pos, Vec3 vel, int lvl) {
            this.owner = owner; this.level = level; this.pos = pos; this.vel = vel; this.lvl = lvl;
        }
    }

    private static final class HaltZone {
        final ServerLevel level; final Vec3 center; long expires;
        final Map<UUID, Vec3> frozenProjVel = new HashMap<>();
        final Set<UUID> frozenMobs = new HashSet<>();
        HaltZone(ServerLevel level, Vec3 center, long expires) { this.level = level; this.center = center; this.expires = expires; }
    }

    private static final Set<UUID> ACTIVE = new HashSet<>();
    private static final Map<UUID, PState> STATE = new HashMap<>();
    private static final List<Ball> BALLS = new ArrayList<>();
    private static final List<HaltZone> HALTS = new ArrayList<>();

    private static PState st(UUID id) { return STATE.computeIfAbsent(id, k -> new PState()); }
    private static long now(ServerPlayer p) { return p.getServer().getTickCount(); }

    // =====================================================
    //  Включение
    // =====================================================
    public static void toggle(ServerPlayer p) {
        if (ACTIVE.remove(p.getUUID())) {
            msg(p, "§7Хроно Варден убран");
            sound(p, SoundEvents.BEACON_DEACTIVATE, 1.0f, 0.9f);
        } else {
            ACTIVE.add(p.getUUID());
            STATE.put(p.getUUID(), new PState());
            msg(p, "§6Хроно Варден призван");
            sound(p, SoundEvents.BEACON_ACTIVATE, 1.0f, 1.2f);
        }
    }

    public static void forget(UUID id) {
        ACTIVE.remove(id);
        STATE.remove(id);
    }

    // =====================================================
    //  Ввод
    // =====================================================
    public static void input(ServerPlayer p, int key, int action) {
        if (!ACTIVE.contains(p.getUUID())) {
            if (action == 0) msg(p, "§cСначала призови Хроно Вардена (ПКМ активатором)");
            return;
        }
        if (!p.isAlive() || p.isSpectator()) return;
        PState s = st(p.getUUID());
        long now = now(p);

        if (key == 0) { // Ball: level решается по времени удержания
            if (action == 0) { s.held[0] = true; s.pressTick = now; return; }
            s.held[0] = false;
            long hold = now - s.pressTick;
            int lvl = hold < BALL_TAP_TICKS ? 1 : (hold < BALL_HOLD_TICKS ? 2 : 3);
            fireBall(p, s, now, lvl);
            return;
        }
        if (action != 0) return; // Anchor/Halt/TS - только по нажатию

        if (now < s.cd[key]) {
            msg(p, "§cещё " + ((s.cd[key] - now + 19) / 20) + " с");
            return;
        }
        boolean ok = switch (key) {
            case 1 -> anchor(p, s, now);
            case 2 -> halt(p, now);
            case 3 -> timestop(p, s, now);
            default -> false;
        };
        if (ok) s.cd[key] = now + COOLDOWN[key];
    }

    // =====================================================
    //  Ball
    // =====================================================
    private static void fireBall(ServerPlayer p, PState s, long now, int lvl) {
        if (now < s.cd[0]) { msg(p, "§cШар: ещё " + ((s.cd[0] - now + 19) / 20) + " с"); return; }
        s.cd[0] = now + COOLDOWN[0];
        Vec3 look = p.getLookAngle();
        BALLS.add(new Ball(p.getUUID(), p.serverLevel(), p.getEyePosition().add(look.scale(1.2)), look.scale(0.8), lvl));
        msg(p, "§6Шар ур." + lvl);
        sound(p, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 0.8f + lvl * 0.2f);
    }

    private static void tickBalls(MinecraftServer server, long now) {
        Iterator<Ball> it = BALLS.iterator();
        while (it.hasNext()) {
            Ball b = it.next();
            ServerPlayer owner = server.getPlayerList().getPlayer(b.owner);
            if (owner == null || !owner.isAlive()) { it.remove(); continue; }
            b.age++;
            b.pos = b.pos.add(b.vel);
            ServerLevel w = b.level;
            w.sendParticles(GOLD_BIG, b.pos.x, b.pos.y, b.pos.z, 2, 0.12, 0.12, 0.12, 0);
            ring(w, b.pos, 0.4, 0.0, GOLD, 8);

            boolean hitSomething = isSolid(w, b.pos) || b.age > 60;
            LivingEntity direct = null;
            for (LivingEntity e : enemiesNear(owner, b.pos, 1.2)) { direct = e; break; }
            if (direct != null) hitSomething = true;

            if (hitSomething) {
                it.remove();
                switch (b.lvl) {
                    case 1 -> ballExplode(owner, w, b.pos, BALL1_DAMAGE, BALL1_RADIUS, false);
                    case 2 -> ballPullThenExplode(owner, w, b.pos);
                    default -> ballTimestopField(owner, w, b.pos);
                }
            }
        }
    }

    private static void ballExplode(ServerPlayer owner, ServerLevel w, Vec3 c, float dmg, float radius, boolean crater) {
        for (LivingEntity e : enemiesNear(owner, c, radius)) {
            e.invulnerableTime = 0;
            e.hurt(w.damageSources().magic(), dmg);
            e.setDeltaMovement(e.getDeltaMovement().add(0, 0.25, 0));
            e.hurtMarked = true;
        }
        w.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        for (int i = 1; i <= 3; i++) ring(w, c, i * (radius / 3.0), 0.0, (i % 2 == 0) ? DARK : GOLD_BIG, 14 + i * 6);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.1f, 1.1f);
    }

    private static void ballPullThenExplode(ServerPlayer owner, ServerLevel w, Vec3 c) {
        long start = now(owner);
        for (int i = 0; i < 4; i++) {
            final int k = i;
            schedule(start + i * 2L, () -> {
                for (LivingEntity e : enemiesNear(owner, c, BALL2_PULL_RADIUS)) {
                    Vec3 d = c.subtract(e.position());
                    if (d.lengthSqr() < 0.25) continue;
                    e.setDeltaMovement(d.normalize().scale(0.55));
                    e.hurtMarked = true;
                }
                ring(w, c, BALL2_PULL_RADIUS - k * 1.5, 0.0, GOLD, 18);
            });
        }
        schedule(start + 10, () -> ballExplode(owner, w, c, BALL2_DAMAGE, BALL2_BLAST_RADIUS, false));
    }

    private static void ballTimestopField(ServerPlayer owner, ServerLevel w, Vec3 c) {
        long now = now(owner);
        HaltZone zone = new HaltZone(w, c, now + BALL3_FREEZE_TICKS);
        for (LivingEntity e : enemiesNear(owner, c, BALL3_FIELD_RADIUS)) {
            zone.frozenMobs.add(e.getUUID());
        }
        HALTS.add(zone);
        ring(w, c, BALL3_FIELD_RADIUS, 0.1, BLUE, 40);
        ring(w, c, BALL3_FIELD_RADIUS * 0.6, 0.1, GOLD, 26);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0f, 1.8f);
        msg(owner, "§bШар ур.3: маленькое поле тайм-стопа");
    }

    // =====================================================
    //  Anchor
    // =====================================================
    private static boolean anchor(ServerPlayer p, PState s, long now) {
        ServerLevel w = p.serverLevel();
        if (!s.anchorSet) {
            s.anchorSet = true;
            s.anchorLevel = w;
            s.anchorPos = p.position();
            s.damageSinceAnchor = 0;
            ring(w, s.anchorPos, 1.0, 0.1, GOLD_BIG, 20);
            w.sendParticles(ParticleTypes.END_ROD, s.anchorPos.x, s.anchorPos.y + 1, s.anchorPos.z, 20, 0.3, 0.5, 0.3, 0.05);
            sound(p, SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, 1.0f, 1.4f);
            msg(p, "§6Якорь поставлен здесь");
            return true;
        }
        // рывок назад
        Vec3 dest = s.anchorPos;
        ServerLevel destLevel = s.anchorLevel;
        float backlash = s.damageSinceAnchor * ANCHOR_BACKLASH_FRACTION;
        s.anchorSet = false;
        if (destLevel == w) {
            burst(w, p.position().add(0, 1, 0), ParticleTypes.REVERSE_PORTAL, 30);
            p.teleportTo(dest.x, dest.y, dest.z);
        } else {
            p.teleportTo(destLevel, dest.x, dest.y, dest.z, p.getYRot(), p.getXRot());
        }
        p.fallDistance = 0;
        p.setHealth(p.getMaxHealth());
        burst(destLevel, dest.add(0, 1, 0), ParticleTypes.REVERSE_PORTAL, 30);
        ring(destLevel, dest, 1.2, 0.1, GOLD_BIG, 24);
        sound(p, SoundEvents.RESPAWN_ANCHOR_DEPLETE, 1.0f, 1.6f);
        if (backlash > 0.5f) {
            for (LivingEntity e : enemiesNear(p, dest, ANCHOR_BACKLASH_RADIUS)) {
                e.invulnerableTime = 0;
                e.hurt(destLevel.damageSources().magic(), backlash);
            }
            msg(p, "§6Якорь: откат + полный хил, отдача " + (int) backlash + " урона врагам рядом");
        } else {
            msg(p, "§6Якорь: откат + полный хил");
        }
        return true;
    }

    /** Вызывается из onHurt: копим урон, полученный с момента установки Якоря. */
    public static void onHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !ACTIVE.contains(p.getUUID())) return;
        PState s = STATE.get(p.getUUID());
        if (s != null && s.anchorSet) s.damageSinceAnchor += Math.max(0, event.getAmount());
    }

    /** Возрождение у Якоря вместо смерти: малое HP, бешеный урон на время. */
    public static boolean onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !ACTIVE.contains(p.getUUID())) return false;
        PState s = STATE.get(p.getUUID());
        if (s == null || !s.anchorSet) return false;
        event.setCanceled(true);
        s.anchorSet = false;
        long now = p.getServer().getTickCount();
        ServerLevel destLevel = s.anchorLevel;
        Vec3 dest = s.anchorPos;
        p.teleportTo(destLevel, dest.x, dest.y, dest.z, p.getYRot(), p.getXRot());
        p.setHealth(Math.max(1.0f, p.getMaxHealth() * ANCHOR_DEATH_HP_FRACTION));
        p.clearFire();
        s.berserkUntil = now + ANCHOR_DEATH_BUFF_TICKS;
        p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, ANCHOR_DEATH_BUFF_TICKS, 3, false, true, true));
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, ANCHOR_DEATH_BUFF_TICKS, 1, false, true, true));
        p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 40, 1, false, true, true));
        burst(destLevel, dest.add(0, 1, 0), ParticleTypes.TOTEM_OF_UNDYING, 60);
        destLevel.playSound(null, dest.x, dest.y, dest.z, SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0f, 0.8f);
        msg(p, "§4§lЯкорь спас тебя §r§6- мало HP, но бешеный урон на " + ANCHOR_DEATH_BUFF_TICKS / 20 + " с");
        return true;
    }

    // =====================================================
    //  Halt
    // =====================================================
    private static boolean halt(ServerPlayer p, long now) {
        ServerLevel w = p.serverLevel();
        Vec3 c = p.getEyePosition().add(p.getLookAngle().scale(HALT_RANGE));
        HaltZone zone = new HaltZone(w, c, now + HALT_DURATION);
        AABB box = new AABB(c.x - HALT_HALF_SIZE, c.y - HALT_HALF_SIZE, c.z - HALT_HALF_SIZE,
                c.x + HALT_HALF_SIZE, c.y + HALT_HALF_SIZE, c.z + HALT_HALF_SIZE);
        for (Projectile pr : w.getEntitiesOfClass(Projectile.class, box, Entity::isAlive)) {
            zone.frozenProjVel.put(pr.getUUID(), pr.getDeltaMovement());
            pr.setDeltaMovement(Vec3.ZERO);
        }
        for (LivingEntity le : w.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != p)) {
            zone.frozenMobs.add(le.getUUID());
        }
        HALTS.add(zone);
        ring(w, c, HALT_HALF_SIZE, 0.0, BLUE, 20);
        sound(p, SoundEvents.GLASS_BREAK, 0.6f, 1.9f);
        msg(p, "§bHalt: зона перед тобой заморожена на " + HALT_DURATION / 20 + " с");
        return true;
    }

    private static void tickHalts(MinecraftServer server, long now) {
        Iterator<HaltZone> it = HALTS.iterator();
        while (it.hasNext()) {
            HaltZone z = it.next();
            if (now >= z.expires) {
                for (Map.Entry<UUID, Vec3> e : z.frozenProjVel.entrySet()) {
                    Entity pr = z.level.getEntity(e.getKey());
                    if (pr != null && pr.isAlive()) pr.setDeltaMovement(e.getValue());
                }
                it.remove();
                continue;
            }
            if (now % 10 == 0) ring(z.level, z.center, HALT_HALF_SIZE, 0.0, BLUE, 12);
            for (UUID id : z.frozenProjVel.keySet()) {
                Entity pr = z.level.getEntity(id);
                if (pr != null && pr.isAlive()) pr.setDeltaMovement(Vec3.ZERO);
            }
            for (UUID id : z.frozenMobs) {
                Entity e = z.level.getEntity(id);
                if (e instanceof LivingEntity le && le.isAlive()) {
                    le.setDeltaMovement(Vec3.ZERO);
                    le.hurtMarked = false;
                    if (le instanceof Mob m) { m.getNavigation().stop(); }
                }
            }
        }
    }

    // =====================================================
    //  Time Stop
    // =====================================================
    private static boolean timestop(ServerPlayer p, PState s, long now) {
        ServerLevel w = p.serverLevel();
        Vec3 c = p.position();
        msg(p, "§6§lЧАСЫ...");
        sound(p, SoundEvents.ANVIL_LAND, 1.5f, 0.5f);
        for (int i = 0; i < TS_WINDUP; i += 4) {
            final int k = i;
            schedule(now + i, () -> {
                clockRing(w, c, 1.5 + k * 0.15);
            });
        }
        schedule(now + TS_WINDUP, () -> startTimestop(p, s, c));
        return true;
    }

    private static void clockRing(ServerLevel w, Vec3 c, double r) {
        ring(w, c, r, 0.1, GOLD_BIG, 28);
        double a = (w.getGameTime() % 60) * Math.PI * 2 / 60;
        Vec3 tip = c.add(Math.cos(a) * r * 0.8, 0.15, Math.sin(a) * r * 0.8);
        w.sendParticles(GOLD, tip.x, tip.y, tip.z, 2, 0, 0, 0, 0);
    }

    private static void startTimestop(ServerPlayer p, PState s, Vec3 center) {
        if (!p.isAlive()) return;
        ServerLevel w = p.serverLevel();
        long now = now(p);
        s.tsActive = true;
        s.tsEndTick = now + TS_DURATION;
        s.tsFrozen.clear();
        for (LivingEntity e : w.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(TS_RADIUS),
                x -> x != p && x.isAlive() && !(x instanceof ArmorStand))) {
            s.tsFrozen.put(e.getUUID(), e.position());
        }
        ring(w, center, TS_RADIUS, 0.1, GOLD_BIG, 60);
        w.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 2.0f, 0.4f);
        msg(p, "§6§lВРЕМЯ ОСТАНОВЛЕНО");
    }

    private static void tickTimestops(MinecraftServer server, long now) {
        for (UUID id : new ArrayList<>(ACTIVE)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            PState s = STATE.get(id);
            if (s == null || !s.tsActive) continue;
            if (now >= s.tsEndTick) {
                s.tsActive = false;
                s.tsFrozen.clear();
                msg(p, "§7Время снова идёт");
                sound(p, SoundEvents.BEACON_DEACTIVATE, 1.2f, 1.0f);
                continue;
            }
            ServerLevel w = p.serverLevel();
            for (Map.Entry<UUID, Vec3> en : s.tsFrozen.entrySet()) {
                Entity e = w.getEntity(en.getKey());
                if (e == null || !e.isAlive()) continue;
                Vec3 frozen = en.getValue();
                e.setPos(frozen.x, frozen.y, frozen.z);
                e.setDeltaMovement(Vec3.ZERO);
                e.fallDistance = 0;
                if (e instanceof Mob m) m.getNavigation().stop();
            }
            if (now % 5 == 0) {
                for (Vec3 pos : s.tsFrozen.values()) {
                    w.sendParticles(GOLD, pos.x, pos.y + 1.0, pos.z, 1, 0.2, 0.3, 0.2, 0);
                }
            }
        }
    }

    // =====================================================
    //  Тик сервера, визуал стенда
    // =====================================================
    private static final List<long[]> EMPTY = List.of();
    private static final List<Runnable> DUE = new ArrayList<>();
    private static final class Task { final long time; final Runnable r; Task(long t, Runnable r) { time = t; this.r = r; } }
    private static final List<Task> TASKS = new ArrayList<>();
    private static void schedule(long time, Runnable r) { TASKS.add(new Task(time, r)); }

    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (!TASKS.isEmpty()) {
            DUE.clear();
            Iterator<Task> it = TASKS.iterator();
            while (it.hasNext()) {
                Task t = it.next();
                if (t.time <= now) { DUE.add(t.r); it.remove(); }
            }
            for (Runnable r : DUE) r.run();
        }
        tickBalls(server, now);
        tickHalts(server, now);
        tickTimestops(server, now);

        if (now % 2 == 0) {
            for (UUID id : ACTIVE) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null && p.isAlive() && !p.isSpectator()) drawStand(p, now);
            }
        }
    }

    private static void drawStand(ServerPlayer p, long now) {
        ServerLevel w = p.serverLevel();
        Vec3 pos = p.position();
        double hy = pos.y + p.getBbHeight() + 0.4;
        double spin = now * 0.05;
        // шестерня: зубцы (8 штук) + обод
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI * 2 / 8 + spin;
            double toothA = a + Math.PI / 16;
            w.sendParticles(BLUE, pos.x + Math.cos(a) * 0.55, hy, pos.z + Math.sin(a) * 0.55, 1, 0, 0, 0, 0);
            w.sendParticles(BLUE, pos.x + Math.cos(toothA) * 0.62, hy, pos.z + Math.sin(toothA) * 0.62, 1, 0, 0, 0, 0);
        }
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI * 2 / 16 + spin;
            w.sendParticles(BLUE, pos.x + Math.cos(a) * 0.4, hy, pos.z + Math.sin(a) * 0.4, 1, 0, 0, 0, 0);
        }
        // циферблат: тёмный центр + 12 засечек + 2 стрелки
        w.sendParticles(DARK, pos.x, hy, pos.z, 1, 0.02, 0.02, 0.02, 0);
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI * 2 / 12;
            w.sendParticles(GOLD, pos.x + Math.cos(a) * 0.27, hy, pos.z + Math.sin(a) * 0.27, 1, 0, 0, 0, 0);
        }
        double minAngle = (now % 100) * Math.PI * 2 / 100;
        double hrAngle = (now % 1200) * Math.PI * 2 / 1200;
        for (double d = 0.05; d < 0.22; d += 0.05) {
            w.sendParticles(GOLD_BIG, pos.x + Math.cos(minAngle) * d, hy, pos.z + Math.sin(minAngle) * d, 1, 0, 0, 0, 0);
        }
        for (double d = 0.05; d < 0.14; d += 0.05) {
            w.sendParticles(GOLD_BIG, pos.x + Math.cos(hrAngle) * d, hy, pos.z + Math.sin(hrAngle) * d, 1, 0, 0, 0, 0);
        }

        PState s = STATE.get(p.getUUID());
        if (s == null) return;
        if (s.anchorSet && s.anchorLevel != null) {
            ring(s.anchorLevel, s.anchorPos, 0.6, 0.1, GOLD, 10);
        }
        if (s.berserkUntil > now) {
            ring(w, pos, 1.0, 0.2, DARK, 12);
        }
    }

    // =====================================================
    //  Утилиты
    // =====================================================
    private static List<LivingEntity> enemiesNear(ServerPlayer p, Vec3 c, double r) {
        double q = r + 1.5;
        AABB box = new AABB(c.x - q, c.y - q, c.z - q, c.x + q, c.y + q, c.z + q);
        return new ArrayList<>(p.serverLevel().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != p && e.isAlive() && !(e instanceof ArmorStand)
                        && e.getBoundingBox().getCenter().distanceToSqr(c) <= r * r));
    }

    private static boolean isSolid(ServerLevel w, Vec3 pos) {
        BlockPos bp = BlockPos.containing(pos);
        BlockState bs = w.getBlockState(bp);
        return !bs.getCollisionShape(w, bp).isEmpty();
    }

    private static void ring(ServerLevel w, Vec3 c, double radius, double yOff, DustParticleOptions eff, int points) {
        for (int i = 0; i < points; i++) {
            double a = i * Math.PI * 2 / points;
            w.sendParticles(eff, c.x + Math.cos(a) * radius, c.y + yOff, c.z + Math.sin(a) * radius, 1, 0, 0, 0, 0);
        }
    }

    private static void burst(ServerLevel w, Vec3 c, net.minecraft.core.particles.ParticleOptions eff, int count) {
        w.sendParticles(eff, c.x, c.y, c.z, count, 0.4, 0.6, 0.4, 0.15);
    }

    private static void sound(ServerPlayer p, net.minecraft.sounds.SoundEvent s, float vol, float pitch) {
        p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), s, SoundSource.PLAYERS, vol, pitch);
    }

    /** Для звуков, которые в этой версии игры хранятся как Holder (например RESPAWN_ANCHOR_*). */
    private static void sound(ServerPlayer p, Holder<net.minecraft.sounds.SoundEvent> s, float vol, float pitch) {
        sound(p, s.value(), vol, pitch);
    }

    private static void msg(ServerPlayer p, String text) {
        p.displayClientMessage(Component.literal(text), true);
    }
}
