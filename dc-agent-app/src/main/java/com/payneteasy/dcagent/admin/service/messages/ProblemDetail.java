package com.payneteasy.dcagent.admin.service.messages;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import javax.annotation.Nullable;
import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Data
@Builder
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class ProblemDetail {
    String type;
    String title;
    String detail;
    int    status;
    String errorId;

    @SerializedName("invalidParams")
    @Nullable
    List<InvalidParam> invalidParams;

    @Data
    @Builder
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class InvalidParam {
         String name;
         String reason;
    }

}
