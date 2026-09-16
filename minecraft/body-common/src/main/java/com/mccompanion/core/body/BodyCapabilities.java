package com.mccompanion.core.body;

import java.util.Set;

/** Executable entry points shared by the two native Body bindings. Never read from the release catalog. */
public final class BodyCapabilities {
    private BodyCapabilities() { }
    public static final Set<String> SKILLS = Set.of(
            "LookAt",
            "InteractBlock",
            "InteractEntity",
            "MenuAction",
            "UseItem",
            "DropItem",
            "AttackEntity",
            "MeleeAttack",
            "ShieldCombat",
            "BowAttack",
            "PlaceBlock",
            "BuildSmallBlueprint",
            "CollectResource",
            "MineResourceVein",
            "WithdrawFromStorage",
            "DepositToStorage",
            "DeliverItem",
            "EatAndRecover",
            "DefendOwner",
            "RetreatFromDanger",
            "CraftItem",
            "SmeltItem",
            "ExploreArea",
            "EquipItem",
            "SleepAtBed",
            "UseWaterBucket",
            "UseVehicle",
            "Fish",
            "FarmCrop",
            "BreedAnimals",
            "TradeWithVillager",
            "EnchantItem",
            "BrewPotion",
            "GlideWithElytra",
            "NavigateWithWorldChanges",
            "FollowEntity",
            "ApproachEntity",
            "KeepDistanceFromEntity",
            "ChaseEntity",
            "EscortEntity",
            "FleeFromEntity",
            "FaceEntity");
}
