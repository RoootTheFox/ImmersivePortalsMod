package qouteall.imm_ptl.core.ducks;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.collision.PortalCollisionHandler;

import javax.annotation.Nullable;

public interface IEEntity {
    void ip_notifyCollidingWithPortal(Entity portal);
    
    @Nullable
    PortalCollisionHandler ip_getPortalCollisionHandler();
    
    PortalCollisionHandler ip_getOrCreatePortalCollisionHandler();
    
    void ip_setPortalCollisionHandler(@Nullable PortalCollisionHandler handler);
    
    @Nullable
    @Deprecated
    Portal getCollidingPortal();
    
    void tickCollidingPortal();
    
    boolean isRecentlyCollidingWithPortal();
    
    void ip_clearCollidingPortal();
    
    void portal_unsetRemoved();
    
    @Nullable
    AABB ip_getActiveCollisionBox(AABB originalBox);
    
    // don't trigger entity section update or other update
    // as we are temporarily switching the position
    void ip_setPositionWithoutTriggeringCallback(Vec3 newPos);
}
