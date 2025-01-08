package com.payneteasy.dcagent.operator.service.services.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStatus;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.services.model.HostServiceItem;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.util.Date;
import java.util.function.Function;

import static com.payneteasy.dcagent.operator.service.services.impl.StatusDescription.description;
import static com.payneteasy.dcagent.operator.service.services.model.StatusIndicator.*;
import static java.lang.System.currentTimeMillis;

public class HostServiceItemMapper {

    private static final ThreadLocal<SimpleDateFormat> WHEN_FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy.MM.dd HH:mm:ss"));

    public static HostServiceItem toHostService(TAgentHost agent, ServiceInfoItem service) {
        String            fqsn        = agent.getName() + "/" + service.getName();
        ServiceStatus     status      = service.getStatus();
        StatusDescription description = createDescription(status);

        return HostServiceItem.builder()
                .fqsn            ( fqsn                  )
                .host            ( agent.getName()       )
                .serviceName     ( service.getName()     )
                .serviceStatus   ( status                )
                .statusName      ( description.getName()        )
                .statusIndicator ( description.getIndicator()   )
                .ageFormatted    ( formatAge(currentTimeMillis(), status.getWhen())  )
                .whenFormatted   ( formatWhen(status.getWhen()) )
                .build();
    }

    private static String formatWhen(Date aWhen) {
        if (aWhen == null) {
            return "";
        }

        return WHEN_FORMAT.get().format(aWhen);
    }

    public static String formatAge(long aCurrentDate, Date aWhen) {
        if (aWhen == null) {
            return "";
        }

        long msInDay = 86_400_000;
        long millis = aCurrentDate - aWhen.getTime();

        Function<Long, LocalDate> longToLocalDate = (epochMs) -> LocalDate.ofEpochDay(epochMs / msInDay);

        if (millis > msInDay) {
            return Period.between(longToLocalDate.apply(aWhen.getTime()), longToLocalDate.apply(aCurrentDate) )
                    .toString()
                    .substring(1);
        }

        return Duration.ofMillis(millis)
                .toString()
                .toLowerCase()
                .substring(2)
                ;
    }

    private static StatusDescription createDescription(ServiceStatus status) {
        if (status.getState() == null) {
            return description("No state", ERROR);
        }

        switch (status.getState()) {
            case UP               : return description("Running"           , SUCCESS );
            case UP_NORMALLY_DOWN : return description("Up, normally down" , SUCCESS );
            case DOWN             : return description("Down"              , STOPPED );
            case DOWN_NORMALLY_UP : return description("Down, normally up" , STOPPED );
            case UP_WANT_DOWN     : return description("Up, want down"     , WARNING );
            case DOWN_WANT_UP     : return description("Down, want up"     , WARNING );
            case UP_PAUSED        : return description("Up, paused"        , LOADING );
            case ERROR            : return description("Error"             , ERROR   );

            default:
                return description("Unknown state: " +status.getState(), ERROR);
        }
    }

}
