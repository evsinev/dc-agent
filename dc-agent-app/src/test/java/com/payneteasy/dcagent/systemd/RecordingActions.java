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

/** Records the changes instead of making them. */
class RecordingActions implements HostActions {
    final List<String> calls = new ArrayList<>();
    boolean root = true;

    @Override public boolean root() { return root; }

    @Override public void setfacl(String aSpec, List<Path> aPaths) { calls.add("setfacl " + aSpec + " " + aPaths); }
    @Override public void chown(Path aPath, String aOwner, String aGroup) { calls.add("chown " + aPath + " " + aOwner + ":" + aGroup); }
    @Override public void chmod(Path aPath, String aMode) { calls.add("chmod " + aPath + " " + aMode); }
    @Override public void mkdir(Path aPath, String aOwner, String aMode) { calls.add("mkdir " + aPath); }
    @Override public void create(Path aPath, String aOwner, String aMode, String aContent) { calls.add("create " + aPath); }
    @Override public void useradd(String aUser) { calls.add("useradd " + aUser); }
    @Override public void sysctl(String aAssignment) { calls.add("sysctl " + aAssignment); }
}
