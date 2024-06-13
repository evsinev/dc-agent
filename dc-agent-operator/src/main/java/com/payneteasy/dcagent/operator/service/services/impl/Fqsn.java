package com.payneteasy.dcagent.operator.service.services.impl;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.StringTokenizer;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class Fqsn {

    String host;
    String serviceName;

    public static Fqsn parseFqsn(String aFqsn) {
        StringTokenizer st = new StringTokenizer(aFqsn, "/");
        return new Fqsn(st.nextToken(), st.nextToken());
    }

}
