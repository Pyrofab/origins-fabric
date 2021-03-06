package io.github.apace100.origins.mixin;

import io.github.apace100.origins.component.OriginComponent;
import io.github.apace100.origins.power.*;
import io.github.apace100.origins.registry.ModBlocks;
import io.github.apace100.origins.registry.ModComponents;
import io.github.apace100.origins.registry.ModTags;
import io.github.apace100.origins.util.Constants;
import net.minecraft.block.BlockState;
import net.minecraft.entity.*;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.tag.FluidTags;
import net.minecraft.tag.Tag;
import net.minecraft.util.Nameable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin extends LivingEntity implements Nameable, CommandOutput {

    @Shadow public abstract boolean damage(DamageSource source, float amount);

    @Shadow protected boolean isSubmergedInWater;

    @Shadow public abstract HungerManager getHungerManager();

    @Shadow public abstract EntityDimensions getDimensions(EntityPose pose);

    @Shadow public abstract void startFallFlying();

    @Shadow public abstract ItemStack getEquippedStack(EquipmentSlot slot);

    protected PlayerEntityMixin(EntityType<? extends LivingEntity> entityType, World world) {
        super(entityType, world);
    }

    // ModifyExhaustion
    @ModifyVariable(at = @At("HEAD"), method = "addExhaustion", ordinal = 0, name = "exhaustion")
    private float modifyExhaustion(float exhaustionIn) {
        OriginComponent component = ModComponents.ORIGIN.get(this);
        for (ModifyExhaustionPower p : component.getPowers(ModifyExhaustionPower.class)) {
            exhaustionIn = exhaustionIn * p.value;
        }
        return exhaustionIn;
    }

    @Inject(at = @At("HEAD"), method = "isUsingEffectiveTool", cancellable = true)
    private void modifyEffectiveTool(BlockState state, CallbackInfoReturnable<Boolean> info) {
        if(state.getBlock().isIn(ModTags.NATURAL_STONE) && PowerTypes.STRONG_ARMS.isActive(this)) {
            info.setReturnValue(true);
        }
    }

    // ModifyDamageDealt
    @ModifyVariable(method = "attack", at = @At(value = "STORE", ordinal = 0), name = "f", ordinal = 0)
    public float modifyDamage(float f) {
        OriginComponent component = ModComponents.ORIGIN.get(this);
        DamageSource source = DamageSource.player((PlayerEntity)(Object)this);
        for (ModifyDamageDealtPower p : component.getPowers(ModifyDamageDealtPower.class)) {
            if (p.doesApply(source)) {
                f = p.apply(f);
            }
        }
        return f;
    }

    // NO_COBWEB_SLOWDOWN
    @Inject(at = @At("HEAD"), method = "slowMovement", cancellable = true)
    public void slowMovement(BlockState state, Vec3d multiplier, CallbackInfo info) {
        if (PowerTypes.NO_COBWEB_SLOWDOWN.isActive(this)) {
            info.cancel();
        }
    }

    // AQUA_AFFINITY
    @ModifyConstant(method = "getBlockBreakingSpeed", constant = @Constant(ordinal = 0, floatValue = 5.0F))
    private float modifyWaterBlockBreakingSpeed(float in) {
        if(PowerTypes.AQUA_AFFINITY.isActive(this)) {
            return 1F;
        }
        return in;
    }

    // AQUA_AFFINITY
    @ModifyConstant(method = "getBlockBreakingSpeed", constant = @Constant(ordinal = 1, floatValue = 5.0F))
    private float modifyUngroundedBlockBreakingSpeed(float in) {
        if(this.isInsideWaterOrBubbleColumn() && PowerTypes.AQUA_AFFINITY.isActive(this)) {
            return 1F;
        }
        return in;
    }

    @Inject(method = "canEquip", at = @At("HEAD"), cancellable = true)
    private void preventArmorDispensing(ItemStack stack, CallbackInfoReturnable<Boolean> info) {
        EquipmentSlot slot = MobEntity.getPreferredEquipmentSlot(stack);
        OriginComponent component = ModComponents.ORIGIN.get(this);
        if(component.getPowers(RestrictArmorPower.class).stream().anyMatch(rap -> !rap.canEquip(stack, slot))) {
            info.setReturnValue(false);
        }
        if(stack.getItem() instanceof ArmorItem) {
            if(PowerTypes.LIGHT_ARMOR.isActive(this)) {
                ArmorItem armor = (ArmorItem)stack.getItem();
                if(armor.getProtection() > Constants.LIGHT_ARMOR_MAX_PROTECTION[armor.getSlotType().getEntitySlotId()]) {
                    info.setReturnValue(false);
                }
            }
        }
    }

    // HUNGER_OVER_TIME & BURN_IN_DAYLIGHT
    // WATER_BREATHING & WATER_VULNERABILITY
    // PARTICLES & EXHAUSTING_WATER_BREATHING
    @Inject(at = @At("TAIL"), method = "tick")
    private void tick(CallbackInfo info) {
        if(!world.isClient) {
            if(this.age % 20 == 0 && PowerTypes.HUNGER_OVER_TIME.isActive(this) && PowerTypes.HUNGER_OVER_TIME.get(this).isActive()) {
                this.getHungerManager().addExhaustion(0.12F);
            }
            if(PowerTypes.BURN_IN_DAYLIGHT.isActive(this) && PowerTypes.BURN_IN_DAYLIGHT.get(this).isActive() && !this.hasStatusEffect(StatusEffects.INVISIBILITY)) {
                if (this.world.isDay() && !this.isRainingAtPlayerPosition()) {
                    float f = this.getBrightnessAtEyes();
                    BlockPos blockPos = this.getVehicle() instanceof BoatEntity ? (new BlockPos(this.getX(), (double)Math.round(this.getY()), this.getZ())).up() : new BlockPos(this.getX(), (double)Math.round(this.getY()), this.getZ());
                    if (f > 0.5F && this.random.nextFloat() * 30.0F < (f - 0.4F) * 2.0F && this.world.isSkyVisible(blockPos)) {
                        this.setOnFireFor(6);
                    }
                }
            }
            if(PowerTypes.WATER_VULNERABILITY.isActive(this)) {
                WaterVulnerabilityPower waterCounter = PowerTypes.WATER_VULNERABILITY.get(this);
                if(this.isWet()) {
                    waterCounter.inWater();
                } else {
                    waterCounter.outOfWater();
                }
            }
            if(this.age % 10 == 0) {
                OriginComponent component = ModComponents.ORIGIN.get(this);
                component.getPowers(SimpleStatusEffectPower.class).forEach(SimpleStatusEffectPower::applyEffects);
                component.getPowers(StackingStatusEffectPower.class, true).forEach(StackingStatusEffectPower::tick);
            }
        }
        if(PowerTypes.WATER_BREATHING.isActive(this)) {
            if(!this.isSubmergedIn(FluidTags.WATER) && !this.hasStatusEffect(StatusEffects.WATER_BREATHING) && !this.hasStatusEffect(StatusEffects.CONDUIT_POWER)) {
                if(!this.isRainingAtPlayerPosition()) {
                    int landGain = this.getNextAirOnLand(0);
                    this.setAir(this.getNextAirUnderwater(this.getAir()) - landGain);
                    if (this.getAir() == -20) {
                        this.setAir(0);
                        Vec3d vec3d = this.getVelocity();

                        for(int i = 0; i < 8; ++i) {
                            double f = this.random.nextDouble() - this.random.nextDouble();
                            double g = this.random.nextDouble() - this.random.nextDouble();
                            double h = this.random.nextDouble() - this.random.nextDouble();
                            this.world.addParticle(ParticleTypes.BUBBLE, this.getParticleX(0.5), this.getEyeY() + this.random.nextGaussian() * 0.08D, this.getParticleZ(0.5), f * 0.5F, g * 0.5F + 0.25F, h * 0.5F);
                        }

                        this.damage(ModDamageSources.NO_WATER_FOR_GILLS, 2.0F);
                    }
                } else {
                    int landGain = this.getNextAirOnLand(0);
                    this.setAir(this.getAir() - landGain);
                }
            } else if(this.getAir() < this.getMaxAir()){
                this.setAir(this.getNextAirOnLand(this.getAir()));
            }
        }
    }

    // Copy from Entity#isBeingRainedOn
    private boolean isRainingAtPlayerPosition() {
        BlockPos blockPos = this.getBlockPos();
        return this.world.hasRain(blockPos) || this.world.hasRain(blockPos.add(0.0D, this.getDimensions(this.getPose()).height, 0.0D));
    }

    // WATER_BREATHING
    @Redirect(at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;isSubmergedIn(Lnet/minecraft/tag/Tag;)Z"), method = "updateTurtleHelmet")
    public boolean isSubmergedInProxy(PlayerEntity player, Tag<Fluid> fluidTag) {
        boolean submerged = this.isSubmergedIn(fluidTag);
        if(PowerTypes.WATER_BREATHING.isActive(this)) {
            return !submerged;
        }
        return submerged;
    }

    // WEBBING
    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;onAttacking(Lnet/minecraft/entity/Entity;)V"), method = "attack")
    public void cobwebOnMeleeAttack(Entity target, CallbackInfo info) {
        if(target instanceof LivingEntity) {
            if(PowerTypes.WEBBING.isActive(this) && !this.isSneaking()) {
                CooldownPower power = PowerTypes.WEBBING.get(this);
                if(power.canUse()) {
                    BlockPos targetPos = target.getBlockPos();
                    if(world.isAir(targetPos) || world.getBlockState(targetPos).getMaterial().isReplaceable()) {
                        world.setBlockState(targetPos, ModBlocks.TEMPORARY_COBWEB.getDefaultState());
                        power.use();
                    }
                }
            }
        }
    }
}
