package com.payneteasy.dcagent.core.modules.docker.filesystem;

import com.payneteasy.dcagent.core.config.model.docker.security.TIdRef;
import com.payneteasy.dcagent.core.config.model.docker.security.TVolumeOwner;
import com.payneteasy.dcagent.core.modules.docker.preflight.PathAttributes;
import com.payneteasy.dcagent.core.modules.docker.resolver.VolumeMode;

import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Difference between the current owner/mode of a directory and the wanted one. Shared by the
 * writer and the checker, so DOCKER_CHECK reports exactly what DOCKER_PUSH changes.
 */
final class OwnerChange {

    private final Integer                  uid;
    private final Integer                  gid;
    private final Set<PosixFilePermission> permissions;
    private final String                   description;

    private OwnerChange(Integer aUid, Integer aGid, Set<PosixFilePermission> aPermissions, String aDescription) {
        uid         = aUid;
        gid         = aGid;
        permissions = aPermissions;
        description = aDescription;
    }

    /**
     * @param aOwner resolved owner (numbers only), fields may be {@code null} = keep
     * @param aMode  e.g. {@code "0770"}, {@code null} = keep
     */
    static OwnerChange of(PathAttributes aCurrent, TVolumeOwner aOwner, String aMode) {
        Integer wantedUid = id(aOwner == null ? null : aOwner.getUser());
        Integer wantedGid = id(aOwner == null ? null : aOwner.getGroup());
        Integer wantedPermissions = aMode == null ? null : Integer.parseInt(aMode, 8);

        List<String> parts = new ArrayList<>();
        Integer uid = null;
        Integer gid = null;
        Set<PosixFilePermission> permissions = null;

        if (wantedUid != null && wantedUid != aCurrent.uid()) {
            uid = wantedUid;
            parts.add("uid " + aCurrent.uid() + " → " + wantedUid);
        }
        if (wantedGid != null && wantedGid != aCurrent.gid()) {
            gid = wantedGid;
            parts.add("gid " + aCurrent.gid() + " → " + wantedGid);
        }
        if (wantedPermissions != null && wantedPermissions != aCurrent.permissions()) {
            permissions = VolumeMode.toPermissions(aMode);
            parts.add("mode " + octal(aCurrent.permissions()) + " → " + octal(wantedPermissions));
        }
        return new OwnerChange(uid, gid, permissions, String.join(", ", parts));
    }

    /** What will be set on a directory that does not exist yet. */
    static String describeNew(TVolumeOwner aOwner, String aMode) {
        List<String> parts = new ArrayList<>();
        Integer uid = id(aOwner == null ? null : aOwner.getUser());
        Integer gid = id(aOwner == null ? null : aOwner.getGroup());
        if (uid != null) {
            parts.add("uid " + uid);
        }
        if (gid != null) {
            parts.add("gid " + gid);
        }
        if (aMode != null) {
            parts.add("mode " + octal(Integer.parseInt(aMode, 8)));
        }
        return String.join(", ", parts);
    }

    boolean isEmpty() {
        return uid == null && gid == null && permissions == null;
    }

    Integer uid() {
        return uid;
    }

    Integer gid() {
        return gid;
    }

    Set<PosixFilePermission> permissions() {
        return permissions;
    }

    String describe() {
        return description;
    }

    private static Integer id(TIdRef aId) {
        if (aId == null) {
            return null;
        }
        if (aId.getRef() != null) {
            throw new IllegalStateException("Owner is not resolved: " + aId);
        }
        return aId.getId();
    }

    private static String octal(int aPermissions) {
        return String.format("%04o", aPermissions);
    }
}
