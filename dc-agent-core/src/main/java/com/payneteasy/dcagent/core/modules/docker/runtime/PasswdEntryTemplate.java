package com.payneteasy.dcagent.core.modules.docker.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code securityContext.passwdEntry}: one {@code /etc/passwd} line with the placeholders podman's
 * {@code --passwd-entry} knows — {@code $USERNAME}, {@code $UID}, {@code $GID}. It goes into the run
 * script in single quotes (podman) or into a file mounted over {@code /etc/passwd} (docker), so only
 * a narrow character set is accepted: no quotes, backslashes, backticks, newlines, and {@code $} only
 * in a placeholder.
 */
public final class PasswdEntryTemplate {

    private static final Pattern ALLOWED     = Pattern.compile("[A-Za-z0-9 _.,:*/+=@$-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$([A-Za-z_]*)");
    private static final Pattern NUMBER      = Pattern.compile("\\d+");

    private PasswdEntryTemplate() {
    }

    /**
     * @return problems of the template, empty when it is valid
     */
    public static List<String> validate(String aTemplate) {
        List<String> errors = new ArrayList<>();
        if (aTemplate.isEmpty() || !ALLOWED.matcher(aTemplate).matches()) {
            errors.add("only letters, digits, space and _ . , : * / + = @ - $ are allowed");
            return errors;
        }
        Matcher placeholders = PLACEHOLDER.matcher(aTemplate);
        while (placeholders.find()) {
            String name = placeholders.group(1);
            if (!name.equals("USERNAME") && !name.equals("UID") && !name.equals("GID")) {
                errors.add("unknown placeholder '$" + name + "', use $USERNAME, $UID or $GID");
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }

        String[] fields = render(aTemplate, 1, 1).split(":", -1);
        if (fields.length != 7) {
            errors.add("needs 7 fields name:password:uid:gid:gecos:home:shell, got " + fields.length);
            return errors;
        }
        if (fields[0].isEmpty()) {
            errors.add("the user name is empty");
        }
        if (!NUMBER.matcher(fields[2]).matches() || !NUMBER.matcher(fields[3]).matches()) {
            errors.add("uid and gid must be numbers or $UID / $GID");
        }
        if (!fields[5].startsWith("/")) {
            errors.add("the home directory must be an absolute path");
        }
        return errors;
    }

    /** {@code $USERNAME} becomes the uid, as podman does for a numeric {@code --user}. */
    public static String render(String aTemplate, int aUid, int aGid) {
        return aTemplate
                .replace("$USERNAME", String.valueOf(aUid))
                .replace("$UID", String.valueOf(aUid))
                .replace("$GID", String.valueOf(aGid));
    }
}
