package com.payneteasy.dcagent.core.config.model;

import com.google.gson.JsonElement;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

/**
 * One entry of a command's {@code keys}. The fields a type uses (and no others) are checked by
 * {@code CommandKeys}: {@link TKeyType#SHA256} — {@code name}, {@code hash};
 * {@link TKeyType#GITLAB_JWT} — {@code name}, {@code issuer}, {@code audience}, {@code rules},
 * optional {@code maxTokenAge}, {@code runnerIds}. Rule values and runner ids stay JSON here: they
 * are normalised by their field's schema, never by Gson's lenient conversions.
 */
@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
@Builder(toBuilder = true)
public class TCommandKey {

    String                         name;
    TKeyType                       type;
    String                         hash;
    String                         issuer;
    String                         audience;
    String                         maxTokenAge;
    List<JsonElement>              runnerIds;
    List<Map<String, JsonElement>> rules;
}
