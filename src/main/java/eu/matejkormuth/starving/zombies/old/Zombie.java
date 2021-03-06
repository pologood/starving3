/**
 * Starving - Bukkit API server mod with Zombies.
 * Copyright (c) 2015, Matej Kormuth <http://www.github.com/dobrakmato>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package eu.matejkormuth.starving.zombies.old;

import eu.matejkormuth.starving.main.NMSHooks;
import eu.matejkormuth.starving.main.bukkitfixes.FlagMetadataValue;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.TrigMath;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftEntity;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_8_R3.util.UnsafeList;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;

@NMSHooks(version = "v1_8_R3")
public class Zombie extends EntityZombie {
    private static final long NAVIGATION_TIMEOUT = 60 * 1000; // 60 seconds
    private static final long NAVIGATION_EPSILON = 5; // 5 blocks

    private static final int HORIZONAL_HALF_FOV = 90 / 2; // 45 degrees
    private static final int VERTICAL_HALF_FOV = 60 / 2; // 30 degrees

    private Entity followingTarget = null;
    private float speed = 0.25f;
    private double followDistanceLimit = 2043f;

    private long navigationStart = Long.MAX_VALUE;
    private boolean navigatingToPoint = false;
    private double navigationX;
    private double navigationY;
    private double navigationZ;

    private long lastJump = Long.MAX_VALUE;
    private boolean disabled;

    protected Zombie(final Location spawnLocation) {
        this(((CraftWorld) spawnLocation.getWorld()).getHandle());
        // Some magic to get zombies to work.
        this.setLocation(spawnLocation.getX(), spawnLocation.getY(),
                spawnLocation.getZ(), spawnLocation.getYaw(),
                spawnLocation.getPitch());
        ((CraftWorld) spawnLocation.getWorld()).getHandle().addEntity(this);

        this.setCustomName("ID:" + this.getId());
        this.setCustomNameVisible(true);

    }

    private Zombie(World world) {
        super(world);
        // Do not burn zombies.
        this.fireProof = true;
        this.maxFireTicks = 0;
        // Remove AI.
        this.removeAI();
        this.getBukkitEntity().setMetadata("starving", new FlagMetadataValue());
    }

    @SuppressWarnings("rawtypes")
    private void removeAI() {
        try {
            Field b = PathfinderGoalSelector.class.getDeclaredField("distanceComparator");
            Field c = PathfinderGoalSelector.class.getDeclaredField("c");
            if (!b.isAccessible()) {
                b.setAccessible(true);
            }
            if (!c.isAccessible()) {
                c.setAccessible(true);
            }
            b.set(this.goalSelector, new UnsafeList());
            c.set(this.goalSelector, new UnsafeList());
            b.set(this.targetSelector, new UnsafeList());
            c.set(this.targetSelector, new UnsafeList());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void K() {
        super.K();
        this.onTick();
    }

    @Override
    protected boolean d(DamageSource arg0, float arg1) {
        if (this.cancelDamage(arg0, arg1)) {
            return false;
        }
        return super.d(arg0, arg1);
    }

    private boolean cancelDamage(DamageSource source, float damage) {
        if (source == DamageSource.STUCK) {
            // Try to escape wall.
            checkInsideWall();
            return true;
        }
        return false;
    }

    private void onTick() {
        if (this.followingTarget != null) {
            this.doFollowTarget();
        } else if (this.navigatingToPoint) {
            // Check for navigation timeout.
            if (this.navigationStart + NAVIGATION_TIMEOUT > System
                    .currentTimeMillis()) {
                this.doNavigateToPoint();
            } else {
                this.cancelNavigation(NavigationFailReason.TIMEOUT);
                return; // Cancel execution of navigation.
            }
        } else {
            // TODO: Only two times per second.

            this.eyeSense();
            this.checkInsideWall();
        }
    }

    private void checkInsideWall() {
        if (this.world.getWorld()
                .getBlockAt((int) locX, (int) locY, (int) locZ).getType()
                .isSolid()) {
            if (this.world.getWorld()
                    .getBlockAt((int) locX, (int) (locY + 1.5d), (int) locZ)
                    .getType().isSolid()) {
                // Inside wall.
                Bukkit.broadcastMessage("Inside wall!");
                double length = Math.sqrt(Math.pow(
                        Math.abs(Math.floor(locX) - locX), 2)
                        + Math.pow(Math.abs(Math.floor(locZ) - locZ), 2));
                double backX = Math.sin(this.yaw) * length;
                double backZ = Math.cos(this.yaw) * length;
                this.updatePosition(locX - backX, locZ - backZ);
            } else {
                // Inside block.
                this.jump();
            }
        }
    }

    private void eyeSense() {
        // We are targeting players.
        for (Player p : Bukkit.getOnlinePlayers()) {
            // Do not target creative players.
            if (p.getGameMode() == GameMode.CREATIVE) {
                continue;
            }

            // Only players in radius of 11.
            if (this.squaredDistanceTo(((CraftPlayer) p).getHandle()) <= 121) {
                // Check for horizontal FOV.
                if (Math.abs(this.yaw - p.getLocation().getYaw()) < HORIZONAL_HALF_FOV) {
                    // Check for vertical FOV.
                    if (Math.abs(this.pitch - p.getLocation().getPitch()) < VERTICAL_HALF_FOV) {
                        // This is our target.
                        this.setFollowTarget(p);
                    }
                }
            }
        }
    }

    public void jump() {
        if (this.lastJump > System.currentTimeMillis() + 50) {
            this.getControllerJump().a();
            this.lastJump = System.currentTimeMillis();
        }
    }

    private void cancelNavigation(NavigationFailReason reason) {
        this.followingTarget = null;
        this.navigatingToPoint = false;
        System.out.println("Navigation of " + this.getId() + " failed: "
                + reason.toString());
        if (reason == NavigationFailReason.ENTITY_DIED) {
            this.setCustomName("SU:" + this.getId());
            this.setCustomNameVisible(true);
        } else {
            this.setCustomName("FA:" + this.getId());
            this.setCustomNameVisible(true);
        }
    }

    private void doNavigateToPoint() {
        // Check if we not reached wanted point.
        double dx = Math.abs(this.navigationX - this.locX);
        double dy = Math.abs(this.navigationY - this.locY);
        double dz = Math.abs(this.navigationZ - this.locZ);
        if (dx < NAVIGATION_EPSILON && dy < NAVIGATION_EPSILON
                && dz < NAVIGATION_EPSILON) {
            this.cancelNavigation(NavigationFailReason.REACHED_TARGET_POINT);
            return; // Cancel execution of navigation.
        } else {
            // Navigate to point

            // We don't need to rotate head each tick, because target point is
            // not moving.

            // Calculate shift and next position.
            double dX = this.navigationX - this.locX;
            double dZ = this.navigationZ - this.locZ;
            // Normalize and multiply by speed.
            double dLength = Math.sqrt(dX * dX + dZ * dZ);
            dX = dX / dLength * this.speed;
            dZ = dZ / dLength * this.speed;
            double nextX = this.locX + dX;
            double nextZ = this.locZ + dZ;

            unstuckGround();

            swingArms();

            if (checkWallhit(nextX, nextZ))
                return;

            // Update position.
            updatePosition(nextX, nextZ);
        }
    }

    private void doFollowTarget() {
        this.setCustomName("FO:" + this.getId() + "->"
                + this.followingTarget.getId());
        this.setCustomNameVisible(true);


        if (checkDeadTarget())
            return;

        if (checkLostSight())
            return;

        // Rotate head.
        rotateBody();

        // Calculate shift and next position.
        double dX = this.followingTarget.locX - this.locX;
        double dZ = this.followingTarget.locZ - this.locZ;
        double dLength = Math.sqrt(dX * dX + dZ * dZ);
        dX = dX / dLength * this.speed;
        dZ = dZ / dLength * this.speed;
        double nextX = this.locX - dX;
        double nextZ = this.locZ - dZ;

        unstuckGround();

        if (checkWallhit(nextX, nextZ))
            return;

        // If is zombie near player damage player.
        if (this.distanceToFollowing() < 1.5F) {
           // Starving.NMS.sendAnimation(this, 0);
            this.followingTarget.damageEntity(DamageSource.mobAttack(this),
                    (float) Math.random() * 2);
        }

        // Update position.
        updatePosition(nextX, nextZ);
    }

    private void updatePosition(double nextX, double nextZ) {
        this.setPositionRotation(nextX, this.locY, nextZ, yaw, pitch);
        this.positionChanged = true;
    }

    private boolean checkWallhit(double nextX, double nextZ) {
        // Don't walk into a block.
        if (this.world.getWorld()
                .getBlockAt((int) nextX, (int) this.locY, (int) nextZ)
                .getType().isSolid()) {
            // If it is a wall, we can't jump over it, navigation fail.
            if (this.world
                    .getWorld()
                    .getBlockAt((int) nextX, (int) (this.locY + 1.5D),
                            (int) nextZ).getType().isSolid()) {
                this.cancelNavigation(NavigationFailReason.HIT_WALL);
                return true; // Cancel execution of navigation.
            } else {
                // Jump over the block.
                this.jump();
            }
        }
        return false;
    }

    private void swingArms() {
        // Swing arms.
        // Starving.NMS.sendAnimation(this, 0);
    }

    private void unstuckGround() {
        // If stuck in ground, jump.
        if (this.world.getWorld()
                .getBlockAt((int) this.locX, (int) this.locY, (int) this.locZ)
                .getType().isSolid()) {
            this.getControllerJump().a();
        }
    }

    private boolean checkLostSight() {
        // Check if zombie lost sight.
        if (distanceToFollowing() > this.followDistanceLimit) {
            // Lost sight.
            this.cancelNavigation(NavigationFailReason.ENTITY_OUT_OF_SIGHT);
            return true; // Cancel execution of navigation.
        }
        return false;
    }

    private void rotateBody() {
        this.yaw = -1
                * (float) (TrigMath.atan2(
                this.followingTarget.locX - this.locX,
                this.followingTarget.locZ - this.locZ) * 180 / Math.PI);
        this.pitch = 0;
        // Update head rotation.
        this.aI = yaw;
    }

    private boolean checkDeadTarget() {
        if (!followingTarget.isAlive()) {
            this.cancelNavigation(NavigationFailReason.ENTITY_DIED);
            return true; // Cancel execution of navigation.
        }
        return false;
    }

    private double distanceToFollowing() {
        return Math.pow((this.locX - this.followingTarget.locX), 2)
                + Math.pow((this.locY - this.followingTarget.locY), 2)
                + Math.pow((this.locZ - this.followingTarget.locZ), 2);
    }

    private double squaredDistanceTo(Entity e) {
        return Math.pow((this.locX - e.locX), 2)
                + Math.pow((this.locY - e.locY), 2)
                + Math.pow((this.locZ - e.locZ), 2);
    }

    public org.bukkit.entity.Entity getFollowTarget() {
        return this.followingTarget.getBukkitEntity();
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public void setFollowTarget(final org.bukkit.entity.Entity e) {
        if (e == null) {
            this.followingTarget = null;
        }

        this.followingTarget = ((CraftEntity) e).getHandle();
    }

    public void navigateTo(double x, double y, double z) {
        this.navigationStart = System.currentTimeMillis();
        this.navigationX = x;
        this.navigationY = y;
        this.navigationZ = z;
        this.navigatingToPoint = true;

        // Rotate zombie.
        this.yaw = -1
                * (float) (TrigMath.atan2(this.navigationX - this.locX,
                this.navigationZ - this.locZ) * 180 / Math.PI);
        this.pitch = 0;
        // Update head rotation.
        this.aI = yaw;
    }

    public void destroy() {
        this.followingTarget = null;
        this.navigatingToPoint = false;
        this.dead = true;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
        // Update disablity.
        if (disabled) {
            // TODO: Enable. this.setInvisible(true);

        } else {
            this.setInvisible(false);

            this.followingTarget = null;
            this.navigatingToPoint = false;
        }
    }

    public boolean isDisabled() {
        return this.disabled;
    }

    public void teleport(Location location) {
        this.getBukkitEntity().teleport(location);
    }

    public org.bukkit.entity.Zombie getBukkitZombieEntity() {
        return (org.bukkit.entity.Zombie) super.getBukkitEntity();
    }

    public CraftEntity getCraftBukkitEntity() {
        return super.getBukkitEntity();
    }

    public static boolean isStarvingZombie(org.bukkit.entity.Entity entity) {
        return entity.hasMetadata("starving");
    }

    public static enum NavigationFailReason {
        ENTITY_OUT_OF_SIGHT, HIT_WALL, TIMEOUT, REACHED_TARGET_POINT, ENTITY_DIED;
    }
}
