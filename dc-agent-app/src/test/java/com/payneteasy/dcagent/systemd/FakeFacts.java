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

/** An invented host: attributes, ACLs, what the agent can do; walk() derives post-order from the paths. */
final class FakeFacts implements HostFacts {
    final Map<Path, Attrs>        attrs     = new HashMap<>();
    final Map<Path, List<String>> acls      = new HashMap<>();
    final Map<Path, List<String>> treeAcls  = new HashMap<>();
    final Map<Path, String>       mounts    = new HashMap<>();
    final Map<Path, String>       obstacles = new HashMap<>();
    final Map<Path, List<String>> lines     = new HashMap<>();
    final Set<String>             agent     = new HashSet<>();
    final Set<String>             users     = new HashSet<>();
    final Set<Path>               unwalkable = new HashSet<>();

    void dir(String aPath, String aOwner, String aGroup, int aMode) {
        attrs.put(Path.of(aPath), new Attrs(Type.DIR, aOwner, aGroup, aMode, 2, 1));
        for (String flag : List.of("-r", "-x", "-w")) {
            agent.add(aPath + " " + flag); // the agent may by default; tests take it away
        }
    }

    void file(String aPath, String aOwner, String aGroup, int aMode, long aLinks) {
        attrs.put(Path.of(aPath), new Attrs(Type.FILE, aOwner, aGroup, aMode, aLinks, 1));
        agent.add(aPath + " -r");
    }

    void fifosOk(String aSupervise) {
        Path s = Path.of(aSupervise);
        attrs.put(s, new Attrs(Type.DIR, "root", "dc-agent", 0710, 2, 1));
        attrs.put(s.resolve("control"), new Attrs(Type.FIFO, "root", "dc-agent", 0620, 1, 1));
        attrs.put(s.resolve("ok"), new Attrs(Type.FIFO, "root", "dc-agent", 0620, 1, 1));
        agent.addAll(List.of(s + " -x", s.resolve("control") + " -w", s.resolve("ok") + " -w"));
    }

    @Override public Optional<Attrs> attrs(Path aPath) { return Optional.ofNullable(attrs.get(aPath)); }
    @Override public List<String> acl(Path aPath) { return acls.getOrDefault(aPath, List.of()); }
    @Override public List<String> aclsInTree(Path aDir) { return treeAcls.getOrDefault(aDir, List.of()); }
    @Override public String mountInside(Path aDir) { return mounts.get(aDir); }
    @Override public String agentObstacle(Path aDir) { return obstacles.get(aDir); }
    @Override public boolean userExists(String aUser) { return users.contains(aUser); }
    @Override public List<String> lines(Path aFile) { return lines.getOrDefault(aFile, List.of()); }

    @Override
    public boolean agentCan(Path aPath, String... aFlags) {
        for (String flag : aFlags) {
            if (!agent.contains(aPath + " " + flag)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<Path> walk(Path aDir) {
        if (unwalkable.contains(aDir)) {
            return null;
        }
        return attrs.keySet().stream().filter(p -> p.startsWith(aDir))
                .sorted(Comparator.comparingInt(Path::getNameCount).reversed().thenComparing(Comparator.naturalOrder())).toList();
    }
}
