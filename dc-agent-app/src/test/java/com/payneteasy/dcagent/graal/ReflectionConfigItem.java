package com.payneteasy.dcagent.graal;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class ReflectionConfigItem {
    String  name;
    Boolean allPublicFields;
    Boolean allDeclaredFields;
    Boolean unsafeAllocated;

    public static ReflectionConfigItem gsonDataClass(Class<?> aClass) {
        return ReflectionConfigItem.builder()
                .name               ( aClass.getName() )
                .allDeclaredFields  ( true  )
                .allPublicFields    ( true  )
                .unsafeAllocated    ( true  )
                .build();
    }
}
