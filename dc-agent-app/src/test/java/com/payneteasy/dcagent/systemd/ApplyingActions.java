package com.payneteasy.dcagent.systemd;

import com.payneteasy.dcagent.systemd.HostFacts.Attrs;
import com.payneteasy.dcagent.systemd.HostFacts.Type;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Makes the changes on the invented host, so a later step's precondition sees them. */
final class ApplyingActions extends RecordingActions {
    private final FakeFacts host;

    ApplyingActions(FakeFacts aHost) {
        host = aHost;
    }

    @Override public void mkdir(Path aPath, String aOwner, String aMode) {
        calls.add("mkdir " + aPath + " " + (aOwner == null ? "root" : aOwner));
        host.dir(aPath.toString(), aOwner == null ? "root" : aOwner, aOwner == null ? "root" : aOwner, 0755);
    }

    @Override public void setfacl(String aSpec, List<Path> aPaths) {
        super.setfacl(aSpec, aPaths);
        for (Path path : aPaths) {
            List<String> acl = new ArrayList<>(host.acl(path));
            acl.add(aSpec);
            host.acls.put(path, acl);
        }
    }
}
