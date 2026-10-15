package rul.templatemod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;

public class TemplateMod implements ClientModInitializer {
    public static boolean enabled = false;
    public static double range = 3.0;
    private static int jumpCooldown = 0;

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
                    context.getSource().sendFeedback(Text.literal("Triggerbot Range set to: " + range));
                    return 1;
                })))
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!enabled || client.player == null || client.world == null || client.interactionManager == null) return;
            if (jumpCooldown > 0) jumpCooldown--;

            PlayerEntity target = null;
            double closestDistance = range;

            for (PlayerEntity player : client.world.getPlayers()) {
                if (player == client.player || !player.isAlive()) continue;
                double distance = client.player.distanceTo(player);
                if (distance < closestDistance) {
                    closestDistance = distance;
                    target = player;
                }
            }

            if (target != null) {
                double dx = target.getX() - client.player.getX();
                double dy = (target.getY() + target.getStandingEyeHeight()) - (client.player.getY() + client.player.getStandingEyeHeight());
                double dz = target.getZ() - client.player.getZ();
                double distanceXZ = Math.sqrt(dx * dx + dz * dz);

                float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
                float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, distanceXZ));

                client.player.setYaw(targetYaw);
                client.player.setPitch(targetPitch);

                if (client.player.isOnGround() && jumpCooldown == 0) {
                    client.player.jump();
                    jumpCooldown = 10;
                }

                if (client.player.getAttackCooldownProgress(0.0f) == 1.0f) {
                    client.interactionManager.attackEntity(client.player, target);
                    client.player.swingHand(Hand.MAIN_HAND);
                }
            }
        });
    }
}