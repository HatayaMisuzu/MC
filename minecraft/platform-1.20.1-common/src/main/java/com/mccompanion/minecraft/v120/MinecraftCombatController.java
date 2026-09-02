package com.mccompanion.minecraft.v120;

import com.mccompanion.core.body.combat.CombatController;
import com.mccompanion.core.body.interaction.EntityTargetIdentity;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.Vec3;

/** Minecraft observations and vanilla input for the shared single-target combat controller. */
final class MinecraftCombatController {
    final EntityTargetIdentity identity;
    private final CombatController.Session session;
    private final String dimension;
    private final PlayerActionGateway gateway;
    private LivingEntity lastTarget;
    private Vec3 previousTargetPosition;
    private float previousHealth = Float.NaN;
    private int attacks;
    private int shots;
    private int damageObservations;
    private boolean equipped;

    MinecraftCombatController(EntityTargetIdentity identity, String capability, int duration,
                              CompanionPlayer body, PlayerActionGateway gateway) {
        this.identity = identity;
        this.gateway = gateway;
        this.dimension = body.serverLevel().dimension().location().toString();
        session = new CombatController.Session(style(capability), duration);
    }

    static boolean supports(String capability) {
        return Set.of("MeleeAttack", "ShieldCombat", "BowAttack").contains(capability);
    }

    static CombatController.Style style(String capability) {
        return switch (capability) {
            case "MeleeAttack" -> CombatController.Style.MELEE;
            case "ShieldCombat" -> CombatController.Style.SHIELD;
            case "BowAttack" -> CombatController.Style.BOW;
            default -> throw new IllegalArgumentException("unsupported combat style");
        };
    }

    void pause(CompanionPlayer body) {
        gateway.cancelUsing(body);
        gateway.stopInput(body);
        session.pause();
        previousTargetPosition = null;
        equipped = false;
    }

    CombatController.Style style() { return session.style(); }

    CombatController.Result fail(String code) { return session.fail(code); }

    CombatController.Result tick(CompanionPlayer body, Entity resolved, int tick) {
        if (!dimension.equals(body.serverLevel().dimension().location().toString())) return fail("WORLD_CHANGED");
        if (resolved != null && resolved.level() != body.level()) return fail("TARGET_WORLD_CHANGED");
        if (resolved != null && !(resolved instanceof LivingEntity)) return fail("ENTITY_NOT_LIVING");
        LivingEntity target = (LivingEntity) resolved;
        // A removed dead entity remains usable as death evidence; disappearance alone is never death.
        if (target == null && lastTarget != null && lastTarget.isDeadOrDying()) target = lastTarget;
        CombatController.Target state = target == null ? CombatController.Target.MISSING
                : target.isDeadOrDying() ? CombatController.Target.DEAD : CombatController.Target.PRESENT;
        boolean damage = target != null && !Float.isNaN(previousHealth) && target.getHealth() < previousHealth;
        if (damage) damageObservations++;
        if (target != null) { lastTarget = target; previousHealth = target.getHealth(); }
        if (state == CombatController.Target.PRESENT && !equipped) {
            String failure = equip(body);
            if (failure != null) return fail(failure);
            equipped = true;
        }
        if (state == CombatController.Target.PRESENT) {
            if (session.style() == CombatController.Style.BOW && !(body.getMainHandItem().getItem() instanceof BowItem))
                return fail("BOW_MISSING");
            if (session.style() == CombatController.Style.SHIELD && !(body.getOffhandItem().getItem() instanceof ShieldItem))
                return fail("SHIELD_MISSING");
        }
        double distance = target == null ? 0 : body.distanceTo(target);
        boolean visible = target != null && body.hasLineOfSight(target);
        boolean inReach = target != null && inAttackReach(body, target);
        boolean threat = target != null && distance <= 4.0 && visible
                && (target instanceof Mob mob && mob.getTarget() == body || target.swinging);
        CombatController.Result result = session.tick(new CombatController.Input(tick, state, visible,
                distance, inReach, body.getAttackStrengthScale(0.0F) >= 1.0F, threat,
                body.isUsingItem(), body.getTicksUsingItem(), damage));
        if (result.status() != CombatController.Status.RUNNING || target == null) {
            gateway.cancelUsing(body);
            gateway.stopInput(body);
            return result;
        }
        Vec3 velocity = previousTargetPosition == null ? Vec3.ZERO : target.position().subtract(previousTargetPosition);
        previousTargetPosition = target.position();
        switch (result.action()) {
            case CHASE, BACK_OFF, HOLD, LOWER -> gateway.cancelUsing(body);
            case ATTACK -> {
                gateway.stopInput(body);
                gateway.lookAt(body, target.getEyePosition());
                gateway.attack(body, target);
                attacks++;
            }
            case RAISE -> {
                gateway.stopInput(body);
                gateway.lookAt(body, target.getEyePosition());
                if (!body.isUsingItem() && !gateway.startUsing(body, InteractionHand.OFF_HAND))
                    return fail("SHIELD_USE_FAILED");
                if (body.getUsedItemHand() != InteractionHand.OFF_HAND) return fail("SHIELD_USE_FAILED");
            }
            case DRAW, AIM, RELEASE -> {
                gateway.stopInput(body);
                // Bounded linear lead and gravity compensation. Vanilla creates and moves the projectile.
                double flightTicks = distance / 3.0;
                if (velocity.lengthSqr() > 1) velocity = Vec3.ZERO;
                Vec3 aim = target.position().add(0, target.getBbHeight() * 0.55, 0)
                        .add(velocity.scale(flightTicks)).add(0, 0.025 * flightTicks * flightTicks, 0);
                gateway.lookAt(body, aim);
                if (result.action() == CombatController.Action.DRAW) {
                    if (body.getProjectile(body.getMainHandItem()).isEmpty()) return fail("AMMO_MISSING");
                    if (!gateway.startUsing(body, InteractionHand.MAIN_HAND)) return fail("BOW_USE_FAILED");
                } else if (result.action() == CombatController.Action.RELEASE) {
                    int ammo = ammoCount(body);
                    Set<UUID> before = arrows(body);
                    gateway.releaseUsing(body);
                    Set<UUID> after = arrows(body);
                    after.removeAll(before);
                    if (after.isEmpty() || body.isUsingItem()) return fail("UNCERTAIN_EFFECT");
                    // Infinity/Creative legitimately conserve ammunition; creation is still mandatory.
                    if (ammoCount(body) > ammo) return fail("UNCERTAIN_EFFECT");
                    shots++;
                }
            }
        }
        return result;
    }

    private String equip(CompanionPlayer body) {
        if (body.containerMenu != body.inventoryMenu || !body.inventoryMenu.getCarried().isEmpty())
            return "COMBAT_INVENTORY_BUSY";
        if (session.style() == CombatController.Style.SHIELD && !(body.getOffhandItem().getItem() instanceof ShieldItem)) {
            int shield = find(body, true);
            if (shield < 0) return "SHIELD_MISSING";
            if (!gateway.equipCombatItem(body, shield, true)) return "EQUIP_NOT_VERIFIED";
        }
        if (session.style() == CombatController.Style.BOW) {
            int bow = find(body, false);
            if (bow < 0) return "BOW_MISSING";
            if (!gateway.equipCombatItem(body, bow, false)) return "EQUIP_NOT_VERIFIED";
        } else {
            int best = body.getInventory().selected;
            double score = weaponScore(body.getMainHandItem());
            for (int slot = 0; slot < 36; slot++) {
                double candidate = weaponScore(body.getInventory().getItem(slot));
                if (candidate > score) { score = candidate; best = slot; }
            }
            if (best != body.getInventory().selected && !gateway.equipCombatItem(body, best, false))
                return "EQUIP_NOT_VERIFIED";
        }
        return null;
    }

    private static int find(CompanionPlayer body, boolean shield) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (shield ? stack.getItem() instanceof ShieldItem : stack.getItem() instanceof BowItem) return slot;
        }
        return -1;
    }

    private static double weaponScore(ItemStack stack) {
        if (stack.isEmpty() || stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage()) return 0;
        return stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE).stream()
                .mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount).sum();
    }

    private static boolean inAttackReach(CompanionPlayer body, LivingEntity target) {
        Vec3 eye = body.getEyePosition();
        var box = target.getBoundingBox();
        Vec3 nearest = new Vec3(Math.max(box.minX, Math.min(eye.x, box.maxX)),
                Math.max(box.minY, Math.min(eye.y, box.maxY)), Math.max(box.minZ, Math.min(eye.z, box.maxZ)));
        return eye.distanceToSqr(nearest) <= 9.0D;
    }

    private static int ammoCount(CompanionPlayer body) {
        int count = 0;
        for (int i = 0; i < body.getInventory().getContainerSize(); i++) {
            ItemStack stack = body.getInventory().getItem(i);
            if (stack.getItem() instanceof ArrowItem) count += stack.getCount();
        }
        return count;
    }

    private static Set<UUID> arrows(CompanionPlayer body) {
        Set<UUID> result = new HashSet<>();
        for (AbstractArrow arrow : body.serverLevel().getEntitiesOfClass(AbstractArrow.class,
                body.getBoundingBox().inflate(4), value -> body.equals(value.getOwner()))) result.add(arrow.getUUID());
        return result;
    }

    Map<String, String> details(CompanionPlayer body, CombatController.Result result) {
        return Map.of("targetId", identity.uuid().toString(), "targetSource", identity.source().name(),
                "style", session.style().name(), "action", result.action().name(),
                "attacks", Integer.toString(attacks), "shots", Integer.toString(shots),
                "damageObservations", Integer.toString(damageObservations),
                "targetHealth", Float.toString(previousHealth), "usingItem", Boolean.toString(body.isUsingItem()));
    }
}
