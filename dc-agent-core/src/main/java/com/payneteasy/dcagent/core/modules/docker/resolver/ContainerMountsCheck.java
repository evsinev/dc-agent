package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.DockerVolume;
import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.config.model.docker.security.TSecurityContext;
import com.payneteasy.dcagent.core.config.model.docker.volumes.IVolume;
import com.payneteasy.dcagent.core.modules.docker.runtime.PasswdEntryTemplate;
import com.payneteasy.dcagent.core.modules.docker.runtime.TmpfsMount;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.payneteasy.dcagent.core.util.SaveList.safeList;

/**
 * {@code securityContext.passwdEntry} and {@code tmpfs}, before anything touches the disk: the
 * template, the tmpfs paths, and mounts that would hide or replace the generated {@code /etc/passwd}
 * (a volume or tmpfs on {@code /}, {@code /etc} or {@code /etc/passwd}: docker would get two mounts
 * of one destination, podman would skip {@code --passwd-entry}).
 */
final class ContainerMountsCheck {

    private static final Path ETC_PASSWD = Paths.get("/etc/passwd");

    private ContainerMountsCheck() {
    }

    static void check(TDocker aDocker) {
        TSecurityContext context = aDocker.getSecurityContext();
        if (context == null) {
            return;
        }
        List<String> errors = new ArrayList<>();

        String passwdEntry = context.getPasswdEntry();
        if (passwdEntry != null) {
            if (context.getRunAsUser() == null || context.getRunAsGroup() == null) {
                errors.add("securityContext.passwdEntry needs runAsUser and runAsGroup: the entry is for that user");
            }
            for (String error : PasswdEntryTemplate.validate(passwdEntry)) {
                errors.add("securityContext.passwdEntry: " + error);
            }
        }

        List<Path> volumeDestinations = new ArrayList<>();
        for (DockerVolume volume : safeList(aDocker.getVolumes())) {
            for (Map.Entry<String, IVolume> entry : volume.allVolumes().entrySet()) {
                String destination = entry.getValue().getDestination();
                if (destination != null && destination.startsWith("/")) {
                    volumeDestinations.add(Paths.get(destination).normalize());
                }
            }
        }

        Set<Path> tmpfsPaths = new HashSet<>();
        List<String> tmpfs = safeList(context.getTmpfs());
        for (int i = 0; i < tmpfs.size(); i++) {
            String label = "securityContext.tmpfs[" + i + "]";
            TmpfsMount mount;
            try {
                mount = TmpfsMount.parse(tmpfs.get(i));
            } catch (IllegalArgumentException e) {
                errors.add(label + ": " + e.getMessage());
                continue;
            }
            Path path = Paths.get(mount.path());
            if (!tmpfsPaths.add(path)) {
                errors.add(label + ": " + path + " is listed twice");
            }
            if (volumeDestinations.contains(path)) {
                errors.add(label + ": " + path + " is also a volume destination");
            }
            if (passwdEntry != null && ETC_PASSWD.startsWith(path)) {
                errors.add(label + ": " + path + " would hide the /etc/passwd of securityContext.passwdEntry");
            }
        }

        if (passwdEntry != null) {
            for (Path destination : volumeDestinations) {
                if (ETC_PASSWD.startsWith(destination)) {
                    errors.add("volumes: a volume on " + destination + " would hide or replace the /etc/passwd of securityContext.passwdEntry");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(String.join("\n  - ", errors));
        }
    }
}
