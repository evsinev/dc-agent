package com.payneteasy.dcagent.operator.service.services.impl;

import com.payneteasy.dcagent.core.remote.agent.controlplane.IDcAgentControlPlaneRemoteService;
import com.payneteasy.dcagent.core.remote.agent.controlplane.client.DcAgentControlPlaneClientFactory;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListRequest;
import com.payneteasy.dcagent.core.remote.agent.controlplane.messages.ServiceListResponse;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceInfoItem;
import com.payneteasy.dcagent.core.remote.agent.controlplane.model.ServiceStatus;
import com.payneteasy.dcagent.operator.service.config.IOperatorConfigService;
import com.payneteasy.dcagent.operator.service.config.model.TAgentHost;
import com.payneteasy.dcagent.operator.service.services.ITraitService;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceListRequest;
import com.payneteasy.dcagent.operator.service.services.messages.HostServiceListResponse;
import com.payneteasy.dcagent.operator.service.services.model.HostServiceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.payneteasy.dcagent.operator.service.services.impl.StatusDescription.description;
import static com.payneteasy.dcagent.operator.service.services.model.StatusIndicator.*;
import static java.lang.System.currentTimeMillis;
import static java.util.Comparator.comparing;

public class TraitServiceImpl implements ITraitService {

    private static final ThreadLocal<SimpleDateFormat> WHEN_FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy.MM.dd HH:mm:ss"));

    private static final Logger LOG = LoggerFactory.getLogger( TraitServiceImpl.class );

    private static final ServiceListRequest LIST_REQUEST = ServiceListRequest.builder().build();

    private final IOperatorConfigService           configService;
    private final DcAgentControlPlaneClientFactory clientFactory;

    public TraitServiceImpl(IOperatorConfigService configService, DcAgentControlPlaneClientFactory clientFactory) {
        this.configService = configService;
        this.clientFactory = clientFactory;
    }

    @Override
    public HostServiceListResponse listServices(HostServiceListRequest aRequest) {
        List<TAgentHost>      agents       = configService.readConfig().getAgents();
        List<HostServiceItem> hostServices = new ArrayList<>();

        for (TAgentHost agent : agents) {
            IDcAgentControlPlaneRemoteService client = createClient(agent);
            try {
                ServiceListResponse   response = client.listServices(LIST_REQUEST);
                List<ServiceInfoItem> services = response.getServices();
                for (ServiceInfoItem service : services) {
                    hostServices.add(toHostService(agent, service));
                }
            } catch (Exception e) {
                LOG.error("Cannot fetch services from {}", agent, e);
                hostServices.add(HostServiceItem.builder()
                        .fqsn(agent.getName() + "/error")
                        .statusName(e.getMessage())
                        .statusIndicator(ERROR)
                        .errorMessage(e.getMessage())
                        .build());
            }
        }

        hostServices.sort(comparing(HostServiceItem::getFqsn));

        return HostServiceListResponse.builder()
                .services(hostServices)
                .build();

    }

    private static HostServiceItem toHostService(TAgentHost agent, ServiceInfoItem service) {
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

    static String formatAge(long aCurrentDate, Date aWhen) {
        if (aWhen == null) {
            return "";
        }


        long millis = aCurrentDate - aWhen.getTime();
        if (millis > 86_400_000) {
            return Period.between(LocalDate.ofEpochDay(aWhen.getTime() / 86400000), LocalDate.now())
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

    private IDcAgentControlPlaneRemoteService createClient(TAgentHost agent) {
        String token   = agent.getCpToken();
        String baseUrl = agent.getUrl();
        return clientFactory.createClient(baseUrl, token);
    }
}
