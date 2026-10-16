package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;

public class ExampleMod implements ClientModInitializer {
    public static boolean enabled = false;
    public static boolean jumpEnabled = true;
    public static double range = 3.0;
    public static double aimSpeed = 0.15;

    private static int jumpCooldown = 0;
    private static boolean waitingToAttack = false;
    private static int attackDelay = 0;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("tbot")
                .then(ClientCommandManager.argument("state", BoolArgumentType.bool())
                .executes(context -> {
                    enabled = BoolArgumentType.getBool(context, "state");
                    context.getSource().sendFeedback(Text.literal("Triggerbot: " + (enabled ? "ON" : "OFF")));
                    return 1;
                }))
                .then(ClientCommandManager.literal("range")
                .then(ClientCommandManager.argument("value", DoubleArgumentType.doubleArg(1.0, 6.0))
                .executes(context -> {
                    range = DoubleArgumentType.getDouble(context, "value");
                    context.getSource().sendFeedback(Text.literal("Range set to: " + range));
                    return 1;
                })))
                .then(ClientCommandManager.literal("jump")
                .then(ClientCommandManager.argument("state", BoolArgumentType.bool())
                .executes(context -> {
                    jumpEnabled = BoolArgumentType.getBool(context, "state");
                    context.getSource().sendFeedback(Text.literal("Jump: " + (jumpEnabled ? "ON" : "OFF")));
                    return 1;
                })))
                .then(ClientCommandManager.literal("smoothaim")
                .then(ClientCommandManager.argument("value", DoubleArgumentType.doubleArg(0.05, 1.0))
                .executes(context -> {
                    aimSpeed = DoubleArgumentType.getDouble(context, "value");
                    context.getSource().sendFeedback(Text.literal("Smooth Aim: " + aimSpeed));
                    return 1;
                })))
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!enabled || client.player == null || client.world == null || client.interactionManager == null) return;

            if (jumpCooldown > 0) jumpCooldown--;
            if (attackDelay > 0) attackDelay--;

            // --- FIND TARGET (120 degree FOV) ---
            PlayerEntity target = null;
            double closestAngle = 60.0;

            for (PlayerEntity player : client.world.getPlayers()) {
                if (player == client.player || !player.isAlive()) continue;

                double distance = client.player.distanceTo(player);
                if (distance > range) continue;

                Vec3d lookVec = client.player.getRotationVec(1.0f);
                Vec3d targetVec = new Vec3d(
                    player.getX() - client.player.getX(),
                    (player.getY() + player.getStandingEyeHeight()) - (client.player.getY() + client.player.getStandingEyeHeight()),
                    player.getZ() - client.player.getZ()
                ).normalize();

                double dot = lookVec.dotProduct(targetVec);
                double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));

                if (angle < closestAngle) {
                    closestAngle = angle;
                    target = player;
                }
            }

            if (target == null) {
                waitingToAttack = false;
                return;
            }

            // --- SMOOTH AIM ---
            double dx = target.getX() - client.player.getX();
            double dy = (target.getY() + target.getStandingEyeHeight()) - (client.player.getY() + client.player.getStandingEyeHeight());
            double dz = target.getZ() - client.player.getZ();
            double distanceXZ = Math.sqrt(dx * dx + dz * dz);

            float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
            float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, distanceXZ));

            float currentYaw = client.player.getYaw();
            float currentPitch = client.player.getPitch();

            float yawDiff = targetYaw - currentYaw;
            while (yawDiff > 180) yawDiff -= 360;
            while (yawDiff < -180) yawDiff += 360;

            client.player.setYaw(currentYaw + (float)(yawDiff * aimSpeed));
            client.player.setPitch(currentPitch + (float)((targetPitch - currentPitch) * aimSpeed));

            // --- JUMP + CRIT LOGIC ---
            boolean cooldownReady = client.player.getAttackCooldownProgress(0.0f) >= 0.9f;

            if (!jumpEnabled) {
                // Jump disabled -> just attack whenever cooldown is ready
                if (cooldownReady && attackDelay == 0) {
                    client.interactionManager.attackEntity(client.player, target);
                    client.player.swingHand(Hand.MAIN_HAND);
                    attackDelay = 1;
                }
                return;
            }

            if (!waitingToAttack) {
                if (client.player.isOnGround() && jumpCooldown == 0 && cooldownReady) {
                    client.player.jump();
                    jumpCooldown = 10;
                    waitingToAttack = true;
                }
            } else {
                boolean falling = !client.player.isOnGround() && client.player.getVelocity().y < 0;

                if (falling && cooldownReady && attackDelay == 0) {
                    client.interactionManager.attackEntity(client.player, target);
                    client.player.swingHand(Hand.MAIN_HAND);
                    waitingToAttack = false;
                    attackDelay = 1;
                } else if (client.player.isOnGround()) {
                    waitingToAttack = false;
                }
            }
        });
    }
}
