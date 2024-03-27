package com.payneteasy.dcagent.operator.service.app.impl;

import com.payneteasy.dcagent.core.util.SafeFiles;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;
import com.payneteasy.dcagent.operator.service.app.IAppService;
import com.payneteasy.dcagent.operator.service.app.model.TApp;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import static com.payneteasy.dcagent.core.util.SafeFiles.ensureFileExists;

public class AppServiceImpl implements IAppService {

    private final File       appDir;
    private final YamlParser yamlParser = new YamlParser();

    public AppServiceImpl(File appDir) {
        this.appDir = appDir;
    }

    @Override
    public TApp getApp(String aName) {
        try {
            File appFile = ensureFileExists(new File(appDir, aName + ".yaml"));
            return yamlParser.parseFile(appFile, TApp.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot file app " + aName, e);
        }
    }


    @Override
    public List<TApp> listApps() {
        return SafeFiles.listFiles(appDir, pathname -> pathname.getName().endsWith(".yaml"))
                .stream()
                .map(it -> yamlParser.parseFile(it, TApp.class))
                .collect(Collectors.toList())
                ;
    }
}
