package com.payneteasy.dcagent.graal;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class ReflectionConfigItem {
    String                          name;
    Boolean                         allPublicFields;
    Boolean                         allDeclaredFields;
    Boolean                         unsafeAllocated;
    List<ReflectionConfigItemField> fields;

    public static ReflectionConfigItem gsonDataClass(Class<?> aClass) {
        return ReflectionConfigItem.builder()
                .name               ( aClass.getName() )
                .allDeclaredFields  ( true  )
                .allPublicFields    ( true  )
                .unsafeAllocated    ( true  )
                .build();
    }

    public static ReflectionConfigItem toReflectionEnum(Class<?> aClass) {
        return ReflectionConfigItem.builder()
                .name               ( aClass.getName() )
                .allDeclaredFields  ( true  )
                .allPublicFields    ( true  )
                .build();

//        return ReflectionConfigItem.builder()
//                .name   ( aClass.getName() )
//                .fields (
//                    stream(aClass.getEnumConstants())
//                        .map     ( Object::toString  )
//                        .map     ( ReflectionConfigItemField::new)
//                        .collect ( toList() )
//                )
//                .build();
    }
}
