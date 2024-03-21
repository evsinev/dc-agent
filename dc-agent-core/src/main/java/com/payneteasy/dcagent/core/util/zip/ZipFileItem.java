package com.payneteasy.dcagent.core.util.zip;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder
public class ZipFileItem {
    String name;
    byte[] bytes;
}
