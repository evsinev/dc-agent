package com.payneteasy.dcagent.admin.dao;

import com.payneteasy.dcagent.admin.dao.model.TClient;
import com.payneteasy.dcagent.admin.dao.model.TRole;
import com.payneteasy.dcagent.admin.dao.model.TUser;

import java.util.Optional;

public interface IAuthDao {

    Optional<TUser> checkUsernameAndPassword(String aUsername, String aPassword);

    Optional<TUser> findUser(String aUsername);

    Optional<TRole> findRole(String aRoleName);

    Optional<TClient> checkClient(String aClientId, String aClientSecret);

}
