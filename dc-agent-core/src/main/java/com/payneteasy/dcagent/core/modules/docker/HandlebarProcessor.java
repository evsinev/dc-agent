package com.payneteasy.dcagent.core.modules.docker;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.payneteasy.dcagent.core.config.model.docker.BoundVariable;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static com.payneteasy.dcagent.core.util.SafeFiles.readFile;

public class HandlebarProcessor {

    private final Handlebars handlebars = new Handlebars();

    public String processTemplate(File aTemplate, List<BoundVariable> aVariables) {
        return processTemplate(readFile(aTemplate), saveList(aVariables));
    }

    private List<BoundVariable> saveList(List<BoundVariable> aVariables) {
        return aVariables != null ? aVariables : Collections.emptyList();
    }

    public String processTemplate(String aTemplate, List<BoundVariable> aVariables) {
        Template template;
        try {
            template = handlebars.compileInline(aTemplate);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compile template" + aTemplate, e);
        }

        Context context = Context.newContext("context");
        for (BoundVariable variable : aVariables) {
            context.data(variable.getName(), variable.getValue());
        }

        try {
            return template.apply(context);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot process template " + aTemplate, e);
        }
    }
}
