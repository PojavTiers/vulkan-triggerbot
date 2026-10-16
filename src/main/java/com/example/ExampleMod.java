package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;

public class ExampleMod implements ClientModInitializer {
    public static boolean enabled = false;
    public static double range = 3.0;
    public static float aimSpeed = 0.15f; // Lower = smoother, higher = snappier

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
                    context.getSource().sendFeedback(Text.literal("Triggerbot Range: " + range));
                    return 1;
                })))
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!enabled || client.player == null || client.world == null || client.interactionManager == null) return;

            // Find the best target within 120 degrees FOV
            PlayerEntity target = null;
            double closestAngle = 60.0; // 60 degrees off-center = 120 degree total cone

            for (PlayerEntity player : client.world.getPlayers()) {
                if (player == client.player || !player.isAlive()) continue;
                
                // Check distance
                double distance = client.player.distanceTo(player);
                if (distance > range) continue;

                // Check FOV (120 degrees total)
                Vec3d lookVec = client.player.getRotationVec(1.0f);
                Vec3d targetVec = new Vec3d(
                    player.getX() - client.player.getX(),
                    (player.getY() + player.getStandingEyeHeight()) - (client.player.getY() + client.player.getStandingEyeHeight()),
                    player.getZ() - client.player.getZ()
                ).normalize();

                double dot = lookVec.dotProduct(targetVec);
                double angle = Math.toDegrees(Math.acos(dot));

                if (angle < closestAngle) {
                    closestAngle = angle;
                    target = player;
                }
            }

            if (target != null) {
                // --- SMOOTH AIM LOGIC ---
                double dx = target.getX() - client.player.getX();
                double dy = (target.getY() + target.getStandingEyeHeight()) - (client.player.getY() + client.player.getStandingEyeHeight());
                double dz = target.getZ() - client.player.getZ();
                double distanceXZ = Math.sqrt(dx * dx + dz * dz);

                float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
                float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, distanceXZ));

                // Smoothly interpolate yaw and pitch
                float currentYaw = client.player.getYaw();
                float currentPitch = client.player.getPitch();

                // Normalize yaw difference to -180 to 180
                float yawDiff = targetYaw - currentYaw;
                while (yawDiff > 180) yawDiff -= 360;
                while (yawDiff < -180) yawDiff += 360;

                client.player.setYaw(currentYaw + yawDiff * aimSpeed);
                client.player.setPitch(currentPitch + (targetPitch - currentPitch) * aimSpeed);

                // --- PACKET CRIT LOGIC ---
                // Only attack if cooldown is ready
                if (client.player.getAttackCooldownProgress(0.0f) == 1.0f) {
                    
                    // Send a fake packet that simulates a tiny fall (forces crit)
                    // This is what Wurst/Meteor do in their "Packet" crit mode
                    double x = client.player.getX();
                    double y = client.player.getY();
                    double z = client.player.getZ();
                    
                    // Send position with onGround=false to trick the server
                    client.player.networkHandler.sendPacket(
                        new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.0625D, z, false)
                    );
                    client.player.networkHandler.sendPacket(
                        new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false)
                    );
                    client.player.networkHandler.sendPacket(
                        new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 1.1E-5D, z, false)
                    );
                    client.player.networkHandler.sendPacket(
                        new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false)
                    );

                    // Now attack (server thinks we're falling = crit)
                    client.interactionManager.attackEntity(client.player, target);
                    client.player.swingHand(Hand.MAIN_HAND);
                }
            }
        });
    }
}
