package com.payneteasy.dcagent.admin.dao.impl;

import com.payneteasy.dcagent.admin.dao.IAuthDao;
import com.payneteasy.dcagent.admin.dao.model.TClient;
import com.payneteasy.dcagent.admin.dao.model.TRole;
import com.payneteasy.dcagent.admin.dao.model.TUser;
import com.payneteasy.dcagent.admin.dao.model.TAuthConfig;
import com.payneteasy.dcagent.core.yaml2json.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Optional;

public class AuthDaoImpl implements IAuthDao {

    private static final Logger LOG = LoggerFactory.getLogger(AuthDaoImpl.class);

    private final File       file;
    private final YamlParser yamlParser = new YamlParser();

    public AuthDaoImpl(File file) {
        this.file = file;
    }

    @Override
    public Optional<TUser> checkUsernameAndPassword(String aUsername, String aPassword) {
        return getConfig()
                .getUsers()
                .stream()
                .filter(user -> user.getUsername().equals(aUsername))
                .filter(user -> user.getPassword().equals(aPassword))
                .findFirst();
    }

    @Override
    public Optional<TUser> findUser(String aUsername) {
        return getConfig()
                .getUsers()
                .stream()
                .filter(user -> user.getUsername().equals(aUsername))
                .findFirst();
    }

    private TAuthConfig getConfig() {
        return yamlParser.parseFile(file, TAuthConfig.class);
    }

    @Override
    public Optional<TRole> findRole(String aRoleName) {
        return getConfig()
                .getRoles()
                .stream()
                .filter(role -> role.getRoleName().equals(aRoleName))
                .findFirst();
    }

    @Override
    public Optional<TClient> checkClient(String aClientId, String aClientSecret) {
        return getConfig()
                .getClients()
                .stream()
                .filter(client -> client.getId().equals(aClientId))
                .filter(client -> client.getSecret().equals(aClientSecret))
                .findFirst();
    }
}
