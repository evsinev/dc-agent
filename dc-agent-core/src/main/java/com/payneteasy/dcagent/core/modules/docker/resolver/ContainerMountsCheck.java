package com.payneteasy.dcagent.core.modules.docker.resolver;

import com.payneteasy.dcagent.core.config.model.docker.DockerVolume;
import com.payneteasy.dcagent.core.config.model.docker.TDocker;
import com.payneteasy.dcagent.core.config.model.docker.security.TSecurityContext;
import com.payneteasy.dcagent.core.config.model.docker.volumes.IVolume;
import com.payneteasy.dcagent.core.modules.docker.runtime.PasswdEntryTemplate;
import com.payneteasy.dcagent.core.modules.docker.runtime.TmpfsMount;

import java.io.File;
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
public final class ContainerMountsCheck {

    private static final Path ETC_PASSWD = Paths.get("/etc/passwd");

    private ContainerMountsCheck() {
    }

    /** Docker's own rule for container names; the name is also the daemontools service directory. */
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

    /** First of all: the name becomes a path (the service directory). */
    public static void checkName(TDocker aDocker) {
        String name = aDocker.getName();
        if (name == null || !NAME.matcher(name).matches()) {
            // anything else fails `docker run --name=` anyway; '/' or '..' would put run outside the services dir
            throw new IllegalStateException("name must match [a-zA-Z0-9][a-zA-Z0-9_.-]* (docker container name), got '" + name + "'");
        }
    }

    static void check(TDocker aDocker) {
        checkName(aDocker);

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

        // the destinations the resolver will produce: relative ones under destinationBaseDir, a
        // missing destination falls back to the source
        List<Path> volumeDestinations = new ArrayList<>();
        for (DockerVolume volume : safeList(aDocker.getVolumes())) {
            for (Map.Entry<String, IVolume> entry : volume.allVolumes().entrySet()) {
                IVolume one = entry.getValue();
                if (one.getDestination() == null && one.getSource() == null) {
                    continue;
                }
                try {
                    File destination = new ResolverContext(aDocker.getDirectories(), null, one.getSource(), one.getDestination(), null, null, null).fullDestination();
                    volumeDestinations.add(destination.toPath().toAbsolutePath().normalize());
                } catch (IllegalStateException e) {
                    // no destinationBaseDir for a relative destination: the resolver reports it
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
