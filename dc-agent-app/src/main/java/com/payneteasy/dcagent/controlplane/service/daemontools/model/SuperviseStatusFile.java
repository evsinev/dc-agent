package com.payneteasy.dcagent.controlplane.service.daemontools.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class SuperviseStatusFile {
    long          when;
    long          pid;
    boolean       paused;
    WantStateType want;
}
