package com.payneteasy.dcagent.admin.service.daemontools.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Date;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class ServiceStatus {

    @Nonnull  ServiceStateType state;
    @Nullable ServiceErrorType error;

              long             pid;
    @Nonnull  Date             when;


}
