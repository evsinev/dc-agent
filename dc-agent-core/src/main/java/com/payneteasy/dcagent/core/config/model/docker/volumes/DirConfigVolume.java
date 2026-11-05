package com.payneteasy.dcagent.core.config.model.docker.volumes;

import com.payneteasy.dcagent.core.config.model.docker.security.TVolumeOwner;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class DirConfigVolume implements IVolume {
    @Getter(onMethod_ = @Override) String  source;
    @Getter(onMethod_ = @Override) String  destination;
    @Getter(onMethod_ = @Override) boolean readonly;
    @Getter(onMethod_ = @Override) TVolumeOwner owner;
    @Getter(onMethod_ = @Override) String       mode;
    String  configPath;

    /** {@code null} — {@link TDirSync#OVERLAY}. */
    TDirSync     sync;

    /** Globs relative to {@code source} that {@link TDirSync#MIRROR} never deletes; only with MIRROR. */
    List<String> keep;

    public boolean isMirror() {
        return sync == TDirSync.MIRROR;
    }
}
