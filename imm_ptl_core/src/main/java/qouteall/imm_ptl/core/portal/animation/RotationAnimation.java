package qouteall.imm_ptl.core.portal.animation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.DQuaternion;

import javax.annotation.Nullable;

public class RotationAnimation implements PortalAnimationDriver {
    public static void init() {
        PortalAnimationDriver.deserializerRegistry.put(
            new ResourceLocation("imm_ptl:rotation"),
            RotationAnimation::deserialize
        );
        
    }
    
    public Vec3 initialPortalOrigin;
    public Vec3 initialPortalDestination;
    public DQuaternion initialPortalOrientation;
    public DQuaternion initialPortalRotation;
    @Nullable
    public Vec3 thisSideRotationCenter;
    @Nullable
    public Vec3 thisSideRotationAxis;
    @Nullable
    public Vec3 otherSideRotationCenter;
    @Nullable
    public Vec3 otherSideRotationAxis;
    public double angularVelocity; // radians per tick
    public long startGameTime;
    public long endGameTime;
    
    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
    
        tag.putString("type", "imm_ptl:rotation");
        Helper.putVec3d(tag, "initialPortalOrigin", initialPortalOrigin);
        Helper.putVec3d(tag, "initialPortalDestination", initialPortalDestination);
        tag.put("initialPortalOrientation", initialPortalOrientation.toTag());
        tag.put("initialPortalRotation", initialPortalRotation.toTag());
        if (thisSideRotationCenter != null) {
            Helper.putVec3d(tag, "thisSideRotationCenter", thisSideRotationCenter);
        }
        if (thisSideRotationAxis != null) {
            Helper.putVec3d(tag, "thisSideRotationAxis", thisSideRotationAxis);
        }
        if (otherSideRotationCenter != null) {
            Helper.putVec3d(tag, "otherSideRotationCenter", otherSideRotationCenter);
        }
        if (otherSideRotationAxis != null) {
            Helper.putVec3d(tag, "otherSideRotationAxis", otherSideRotationAxis);
        }
        tag.putDouble("angularVelocity", angularVelocity);
        tag.putLong("startGameTime", startGameTime);
        tag.putLong("endGameTime", endGameTime);
        
        return tag;
    }
    
    private static RotationAnimation deserialize(CompoundTag tag) {
        RotationAnimation animation = new RotationAnimation();
        animation.initialPortalOrigin = Helper.getVec3d(tag, "initialPortalOrigin");
        animation.initialPortalDestination = Helper.getVec3d(tag, "initialPortalDestination");
        animation.initialPortalOrientation = DQuaternion.fromTag(tag.getCompound("initialPortalOrientation"));
        animation.initialPortalRotation = DQuaternion.fromTag(tag.getCompound("initialPortalRotation"));
        animation.thisSideRotationCenter = Helper.getVec3dOptional(tag, "thisSideRotationCenter");
        animation.thisSideRotationAxis = Helper.getVec3dOptional(tag, "thisSideRotationAxis");
        animation.otherSideRotationCenter = Helper.getVec3dOptional(tag, "otherSideRotationCenter");
        animation.otherSideRotationAxis = Helper.getVec3dOptional(tag, "otherSideRotationAxis");
        animation.angularVelocity = tag.getDouble("angularVelocity");
        animation.startGameTime = tag.getLong("startGameTime");
        animation.endGameTime = tag.getLong("endGameTime");
        return animation;
    }
    
    @Override
    public boolean update(Portal portal, long tickTime, float tickDelta) {
        double passedTicks = ((double) (tickTime - startGameTime)) + tickDelta;
        
        boolean ends = false;
        if (passedTicks > (endGameTime - startGameTime)) {
            ends = true;
            passedTicks = endGameTime - startGameTime;
        }
        
        double angle = angularVelocity * passedTicks;
        if (thisSideRotationCenter != null && thisSideRotationAxis != null) {
            DQuaternion rotation = DQuaternion.rotationByRadians(thisSideRotationAxis, angle);
            
            Vec3 offset = initialPortalOrigin.subtract(thisSideRotationCenter);
            Vec3 rotatedOffset = rotation.rotate(offset);
            portal.setOriginPos(thisSideRotationCenter.add(rotatedOffset));
            
            portal.setOrientationRotation(rotation.hamiltonProduct(initialPortalOrientation));
        }
        
        if (otherSideRotationCenter != null && otherSideRotationAxis != null) {
            DQuaternion rotation = DQuaternion.rotationByRadians(otherSideRotationAxis, angle);
            
            Vec3 offset = initialPortalDestination.subtract(otherSideRotationCenter);
            Vec3 rotatedOffset = rotation.rotate(offset);
            portal.setDestination(otherSideRotationCenter.add(rotatedOffset));
            
            portal.setRotationTransformationD(rotation.hamiltonProduct(initialPortalRotation));
        }
        
        return ends;
    }
    
}