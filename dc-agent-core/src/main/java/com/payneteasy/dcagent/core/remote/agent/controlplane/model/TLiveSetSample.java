package com.payneteasy.dcagent.core.remote.agent.controlplane.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

/**
 * One point in the old-generation-after-GC series (see {@code TGcInfo.oldGenSamples}): the wall-clock
 * of a collection and the old-gen bytes still live right after it. The operator fits a growth rate over
 * the real time window these span — the honest signal for a slow leak, unlike comparing two adjacent
 * collections. Primitive fields are fine here: only current agents ever emit these, and the whole list
 * is {@code null} on an older agent.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class TLiveSetSample {

    long epochMs;    // wall-clock of the collection
    long usedBytes;  // old-gen used right after it
}
